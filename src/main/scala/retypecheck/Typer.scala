package retypecheck

import org.scalamacros.resetallattrs._
import scala.collection.mutable
import scala.reflect.macros.blackbox.Context
import scala.reflect.macros.TypecheckException

object Typer {
  def apply[C <: Context](c: C): Typer[c.type] =
    new Typer[c.type](c)
}

/**
 * heavy wizardry to fight the dark forces of Scala type-checking in macros
 */
class Typer[C <: Context](val c: C) {
  import c.universe._
  import Flag._

  /**
   * Re-type-checks the given tree, i.e., first un-type-checks it and then
   * type-checks it again using [[untypecheck]] and [[typecheck]], respectively.
   */
  def retypecheck(tree: Tree): Tree =
    typecheck(untypecheck(tree))

  /**
   * Re-type-checks the given tree resetting all symbols using the
   * `org.scalamacros.resetallattrs` library, i.e., first un-type-checks it and
   * then type-checks it again using [[untypecheckAll]] and [[typecheck]],
   * respectively.
   */
  def retypecheckAll(tree: Tree): Tree =
    typecheck(untypecheckAll(tree))

  /**
   * Type-checks the given tree. If type-checking fails, aborts the macro
   * expansion issuing the type-checking error.
   *
   * The type-checking process distorts certain ASTs (such as representations of
   * extractors, lazy values or case classes) in a way that they cannot be
   * type-checked again. The issue is described in
   * [[https://issues.scala-lang.org/browse/SI-5464 SI-5465]].
   *
   * This method tries to restore the AST to its original form, which can be
   * type-checked again.
   */
  def typecheck(tree: Tree): Tree = {
    try
      fixTypecheck(
        (syntheticTreeMarker transform
          (c typecheck
            (nonSyntheticTreeMarker transform tree))),
        abortWhenUnfixable = false)
    catch {
      case TypecheckException(pos, msg) =>
        c.abort(pos.asInstanceOf[Position], msg)
    }
  }

  /**
   * Un-type-checks the given tree.
   *
   * The type-checking process distorts certain ASTs (such as representations of
   * extractors, lazy values or case classes) in a way that they cannot be
   * type-checked again. The issue is described in
   * [[https://issues.scala-lang.org/browse/SI-5464 SI-5465]].
   *
   * This method tries to restore the AST to its original form, which can be
   * type-checked again, or abort the macro expansion if this is not possible.
   */
  def untypecheck(tree: Tree): Tree =
    c untypecheck
      (typeApplicationCleaner transform
        (syntheticImplicitParamListCleaner transform
          fixTypecheck(tree, abortWhenUnfixable = true)))

  /**
   * Un-type-checks the given tree resetting all symbols using the
   * `org.scalamacros.resetallattrs` library.
   *
   * The type-checking process distorts certain ASTs (such as representations of
   * extractors, lazy values or case classes) in a way that they cannot be
   * type-checked again. The issue is described in
   * [[https://issues.scala-lang.org/browse/SI-5464 SI-5465]].
   *
   * This method tries to restore the AST to its original form, which can be
   * type-checked again, or abort the macro expansion if this is not possible.
   */
  def untypecheckAll(tree: Tree): Tree =
    c resetAllAttrs
      (selfReferenceFixer transform
        (typeApplicationCleaner transform
          (syntheticImplicitParamListCleaner transform
            fixTypecheck(tree, abortWhenUnfixable = true))))

  /**
   * Cleans the flag set of the given modifiers.
   *
   * The type-checking process annotates definitions with various flags. Some
   * of them can also be inserted by user-code or even have a corresponding
   * Scala language construct, but others are only used by the type-checker.
   * Certain flags can interfere with type-checking and cause it to fail. Those
   * flags can be safely removed and will be re-inserted during type-checking
   * when needed.
   *
   * This method eliminates some problematic cases.
   */
  def cleanModifiers(mods: Modifiers): Modifiers = {
    val possibleFlags = Seq(ABSTRACT, ARTIFACT, BYNAMEPARAM, CASE, CASEACCESSOR,
      CONTRAVARIANT, COVARIANT, DEFAULTINIT, DEFAULTPARAM, DEFERRED, ENUM,
      FINAL, IMPLICIT, LAZY, LOCAL, MACRO, MUTABLE, OVERRIDE, PARAM,
      PARAMACCESSOR, PRESUPER, PRIVATE, PROTECTED, SEALED, SYNTHETIC)

    val flags = possibleFlags.fold(NoFlags) { (flags, flag) =>
      if (mods hasFlag flag) flags | flag else flags
    }
    
    Modifiers(flags, mods.privateWithin, mods.annotations)
  }


  /**
   * Creates an AST representing the given type.
   *
   * The type-checking process creates synthetic type trees and it is possible
   * to insert trees with type information, but it is not easily possible to
   * create an AST for a given type.
   *
   * This method attempts to create such an AST, which is persistent across
   * type-checking and un-type-checking.
   */
  def createTypeTree(tpe: Type): Tree = {
    def isClass(symbol: Symbol): Boolean =
      symbol.isClass && !symbol.isModule && !symbol.isPackage

    def expandSymbol(symbol: Symbol): Tree = {
      if (symbol.owner != NoSymbol)
        Select(expandSymbol(symbol.owner), symbol.name.toTermName)
      else
        Ident(termNames.ROOTPKG)
    }

    def expandType(tpe: Type): Tree = tpe.dealias match {
      case ThisType(pre) if isClass(pre) =>
        This(pre.asType.name)

      case ThisType(pre) =>
        expandSymbol(pre)

      case TypeRef(NoPrefix, sym, args) =>
        val ident = Ident(sym.name.toTypeName)
        if (!args.isEmpty)
          AppliedTypeTree(ident, args map expandType)
        else
          ident

      case TypeRef(pre, sym, args) =>
        val preTree = expandType(pre)

        val typeProjection = preTree match {
          case This(_) => false
          case _ => isClass(pre.typeSymbol)
        }

        val select =
          if (typeProjection)
            preTree match {
              case SingletonTypeTree(ref) =>
                Select(ref, sym.name.toTypeName)
              case _ =>
                SelectFromTypeTree(preTree, sym.name.toTypeName)
            }
          else if (isClass(sym))
            Select(preTree, sym.name.toTypeName)
          else
            Select(preTree, sym.name.toTermName)

        if (!args.isEmpty)
          AppliedTypeTree(select, args map expandType)
        else
          select

      case SingleType(NoPrefix, sym) =>
        SingletonTypeTree(Ident(sym.name.toTermName))

      case SingleType(pre, sym) =>
        val preTree = expandType(pre) match {
          case SingletonTypeTree(pre) => pre
          case pre => pre
        }

        SingletonTypeTree(Select(preTree, sym.name.toTermName))

      case TypeBounds(lo, hi) =>
        TypeBoundsTree(
          if (lo =:= definitions.NothingTpe) EmptyTree else expandType(lo),
          if (hi =:= definitions.AnyTpe) EmptyTree else expandType(hi))

      case ExistentialType(quantified, underlying) =>
        val whereClauses = quantified map { quantified =>
          quantified.typeSignature match {
            case TypeBounds(lo, hi) =>
              val name = quantified.name.toString
              val mods = Modifiers(
                DEFERRED | (if (quantified.isSynthetic) SYNTHETIC else NoFlags))

              if (!(name endsWith ".type"))
                Some(TypeDef(
                  Modifiers(DEFERRED | SYNTHETIC),
                  TypeName(name),
                  List.empty,
                  expandType(quantified.typeSignature)))
              else if (lo =:= definitions.NothingTpe)
                Some(ValDef(
                  Modifiers(DEFERRED),
                  TermName(name substring (0, name.size - 5)),
                  expandType(hi),
                  EmptyTree))
              else
                None

            case _ =>
              None
          }
        }

        if (whereClauses exists { _.isEmpty })
          TypeTree(tpe)
        else
          ExistentialTypeTree(
            expandType(underlying),
            whereClauses collect { case Some(whereClause) => whereClause })
        
      case _ =>
        TypeTree(tpe)
    }

    expandType(tpe)
  }


  private case object NonSyntheticTree

  private case object SyntheticTree

  private object nonSyntheticTreeMarker extends Transformer {
    override def transform(tree: Tree) = tree match {
      case Apply(_, _) =>
        internal updateAttachment (tree, NonSyntheticTree)
        internal removeAttachment[SyntheticTree.type] tree
        super.transform(tree)

      case _ =>
        internal removeAttachment[SyntheticTree.type] tree
        internal removeAttachment[NonSyntheticTree.type] tree
        super.transform(tree)
    }
  }

  private object syntheticTreeMarker extends Transformer {
    val processedMethodTrees = mutable.Set.empty[Tree]

    override def transform(tree: Tree) = tree match {
      case Apply(fun, _) =>
        if (!(processedMethodTrees contains tree)) {
          val hasImplicitParamList =
            tree.symbol != null &&
            tree.symbol.isMethod &&
            (tree.symbol.asMethod.paramLists.lastOption flatMap {
              _.headOption map { _.isImplicit }
            } getOrElse false)

          val isNonSyntheticParamList =
            (internal attachments tree).get[NonSyntheticTree.type].nonEmpty

          internal removeAttachment[NonSyntheticTree.type] tree

          if (hasImplicitParamList && !isNonSyntheticParamList) {
            internal updateAttachment (tree, SyntheticTree)
            processedMethodTrees += fun
          }
        }
        else
          processedMethodTrees += fun

        super.transform(tree)

      case _ =>
        super.transform(tree)
    }
  }


  private object syntheticImplicitParamListCleaner extends Transformer {
    override def transform(tree: Tree) = tree match {
      case Apply(fun, _) =>
        if ((internal attachments tree).get[SyntheticTree.type].nonEmpty)
          transform(fun)
        else
          super.transform(tree)

      case _ =>
        super.transform(tree)
    }
  }


  private object typeApplicationCleaner extends Transformer {
    def prependRootPackage(tree: Tree): Tree = tree match {
      case Ident(termNames.ROOTPKG) =>
        tree
      case Ident(name) if tree.symbol.owner.owner == NoSymbol =>
        Select(Ident(termNames.ROOTPKG), name)
      case Select(qualifier, name) =>
        Select(prependRootPackage(qualifier), name)
      case _ =>
        tree
    }

    override def transform(tree: Tree) = tree match {
      case tree: TypeTree =>
        if (tree.original != null)
          transform(prependRootPackage(tree.original))
        else if (tree.tpe != null)
          createTypeTree(tree.tpe)
        else
          tree

      case ValDef(mods, name, tpt, rhs) if mods hasFlag ARTIFACT =>
        val valDef = ValDef(
          transformModifiers(mods), name, tpt,
          transform(rhs))
        internal setSymbol (valDef, tree.symbol)
        internal setType (valDef, tree.tpe)
        internal setPos (valDef, tree.pos)

      case Apply(TypeApply(fun, targs), args) =>
        val hasImplicitParamList =
          tree.symbol != null &&
          tree.symbol.isMethod &&
          (tree.symbol.asMethod.paramLists.lastOption flatMap {
            _.headOption map { _.isImplicit }
          } getOrElse false)

        val hasNonRepresentableType = targs exists { arg =>
          arg.tpe != null && (arg.tpe exists {
            case TypeRef(NoPrefix, name, List()) =>
              name.toString endsWith ".type"
            case _ =>
              false
          })
        }

        if (hasImplicitParamList && hasNonRepresentableType)
          transform(fun)
        else
          super.transform(tree)

      case DefDef(_, termNames.CONSTRUCTOR, _, _, _, _) =>
        tree

      case _ =>
        super.transform(tree)
    }
  }


  private def selfReferenceFixer = new Transformer {
    val stack = mutable.Stack.empty[(TypeName, Set[Name])]

    override def transform(tree: Tree) = tree match {
      case implDef: ImplDef =>
        stack push implDef.name.toTypeName -> (
          (implDef.impl.parents flatMap { parent =>
            if (parent.symbol.isType)
              parent.symbol.asType.toType.members map { _.name }
            else
              Iterable.empty
          }) ++
          (implDef.impl.body collect {
            case defTree: DefTree => defTree.name
          })
        ).toSet

        val tree = super.transform(implDef)
        stack.pop
        tree

      case Select(thisTree @ This(thisName), selectedName) =>
        val lookupResult =
          if (thisName.toString startsWith "$anon")
            Some(false)
          else
            stack collectFirst {
              case (name, names) if name == thisName =>
                names contains selectedName
            }

        lookupResult match {
          case Some(false) =>
            val ident = Ident(selectedName)
            if (tree.pos != NoPosition)
              internal setPos (ident, tree.pos)
            else
              internal setPos (ident, thisTree.pos)
            internal setType (ident, tree.tpe)

          case _ =>
            if (thisTree.symbol.isModuleClass &&
                !thisTree.symbol.isPackage &&
                !thisTree.symbol.isPackageClass)
              Select(Ident(thisName.toTermName), selectedName)
            else
              tree
        }

      case _ =>
        super.transform(tree)
    }
  }

  private implicit class TermOps(term: TermSymbol) {
    def getterOrNoSymbol =
      try term.getter
      catch { case _: reflect.internal.Symbols#CyclicReference => NoSymbol }
    def setterOrNoSymbol =
      try term.setter
      catch { case _: reflect.internal.Symbols#CyclicReference => NoSymbol }
  }

  private def fixTypecheck(tree: Tree, abortWhenUnfixable: Boolean): Tree = {
    val rhss = (tree collect {
      case valDef @ ValDef(_, _, _, _) if valDef.symbol.isTerm =>
        val term = valDef.symbol.asTerm
        List(term.getterOrNoSymbol -> valDef, term.setterOrNoSymbol -> valDef)
    }).flatten.toMap - NoSymbol

    object typecheckFixer extends Transformer {
      override def transform(tree: Tree) = tree match {
        // fix extractors
        case UnApply(
            Apply(fun, List(Ident(TermName("<unapply-selector>")))), args) =>
          fun collect {
            case Select(fun, TermName("unapply" | "unapplySeq")) => fun
          } match {
            case fun :: _ =>
              val apply = Apply(fun, args)
              internal setType (apply, fun.tpe)
              internal setPos (apply, fun.pos)
              transform(apply)
            case _ =>
              transform(tree)
          }

        // fix vars, vals and lazy vals
        case ValDef(_, _, _, _)
            if tree.symbol.isTerm && {
              val term = tree.symbol.asTerm
              term.getterOrNoSymbol != NoSymbol &&
                (term.isLazy || (rhss contains term.getter))
            } =>
          EmptyTree

        // fix vars and vals
        case DefDef(_, _, _, _, _, _)
            if tree.symbol.isTerm && {
              val term = tree.symbol.asTerm
              term.isSetter && (rhss contains term)
            } =>
          EmptyTree
        case defDef @ DefDef(mods, name, _, _, tpt, _)
            if tree.symbol.isTerm && {
              val term = tree.symbol.asTerm
              !term.isLazy && term.isGetter && (rhss contains term)
            } =>
          val valDef = rhss(tree.symbol)
          val flags = cleanModifiers(mods).flags |
            (if (valDef.symbol.asTerm.isVar) MUTABLE else NoFlags)
          val privateWithin =
            if (defDef.symbol.asTerm.privateWithin != NoSymbol)
              defDef.symbol.asTerm.privateWithin.name
            else
              mods.privateWithin
          val defAnnotations =
            defDef.symbol.annotations map {
              annotation => transform(annotation.tree)
            } filterNot { annotation =>
              mods.annotations exists { _ equalsStructure annotation }
            }
          val valAnnotations =
            rhss(tree.symbol).symbol.annotations map {
              annotation => transform(annotation.tree)
            } filterNot { annotation =>
              (mods.annotations exists { _ equalsStructure annotation }) ||
              (defAnnotations exists { _ equalsStructure annotation })
            }
          val annotations = mods.annotations ++ defAnnotations ++ valAnnotations
          val newValDef = ValDef(
            Modifiers(flags, privateWithin, annotations),
            name, transform(valDef.tpt), transform(valDef.rhs))
          internal setType (newValDef, valDef.tpe)
          internal setPos (newValDef, valDef.pos)

        // fix lazy vals
        case defDef @ DefDef(mods, name, _, _, tpt, rhs)
            if tree.symbol.isTerm && {
              val term = tree.symbol.asTerm
              term.isLazy && term.isGetter
            } =>
          val assignment = rhs collect {
            case Assign(_, rhs) => rhs
          } match {
            case rhs :: _ => rhs
            case _ => rhs
          }
          val valDef = rhss get tree.symbol
          val typeTree = valDef map { _.tpt } getOrElse tpt
          val flags = cleanModifiers(mods).flags
          val privateWithin =
            if (defDef.symbol.asTerm.privateWithin != NoSymbol)
              defDef.symbol.asTerm.privateWithin.name
            else
              mods.privateWithin
          val defAnnotations =
            defDef.symbol.annotations map {
              annotation => transform(annotation.tree)
            } filterNot { annotation =>
              mods.annotations exists { _ equalsStructure annotation }
            }
          val valAnnotations =
            (rhss get tree.symbol).toList flatMap { _.symbol.annotations } map {
              annotation => transform(annotation.tree)
            } filterNot { annotation =>
              (mods.annotations exists { _ equalsStructure annotation }) ||
              (defAnnotations exists { _ equalsStructure annotation })
            }
          val annotations = mods.annotations ++ defAnnotations ++ valAnnotations
          val newValDef = ValDef(
            Modifiers(flags, privateWithin, annotations),
            name, transform(typeTree), transform(assignment))
          valDef map { valDef =>
            internal setType (newValDef, valDef.tpe)
            internal setPos (newValDef, valDef.pos)
          } getOrElse newValDef

        // fix defs
        case defDef @ DefDef(mods, name, tparams, vparamss, tpt, rhs)
            if tree.symbol.isTerm =>
          val flags = cleanModifiers(mods).flags
          val privateWithin =
            if (defDef.symbol.asTerm.privateWithin != NoSymbol)
              defDef.symbol.asTerm.privateWithin.name
            else
              mods.privateWithin
          val defAnnotations =
            defDef.symbol.annotations map {
              annotation => transform(annotation.tree)
            } filterNot { annotation =>
              mods.annotations exists { _ equalsStructure annotation }
            }
          val annotations = mods.annotations ++ defAnnotations
          if (defDef.symbol.asTerm.privateWithin != NoSymbol ||
              defAnnotations.nonEmpty ||
              mods.flags != flags ||
              tree.symbol.name == TermName("$init$")) {
            val newDefDef = DefDef(
              Modifiers(flags, privateWithin, annotations), name,
              transformTypeDefs(tparams),
              transformValDefss(vparamss),
              transform(tpt),
              transform(rhs))
            internal setType (newDefDef, defDef.tpe)
            internal setPos (newDefDef, defDef.pos)
          }
          else
            super.transform(tree)

        // abort on case class
        case ClassDef(mods, _, _, _)
            if (mods hasFlag CASE) && abortWhenUnfixable =>
          c.abort(tree.pos, "case class not allowed inside macro application")

        case _ =>
          super.transform(tree)
      }
    }

    typecheckFixer transform tree
  }
}
