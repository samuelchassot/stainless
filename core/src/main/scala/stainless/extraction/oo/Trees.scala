/* Copyright 2009-2021 EPFL, Lausanne */

package stainless
package extraction
package oo

import scala.collection.mutable.Map as MutableMap

trait Trees extends innerfuns.Trees with Definitions { self =>

  /* ========================================
   *              EXPRESSIONS
   * ======================================== */

  /** $encodingof `new id(args)` */
  case class ClassConstructor(ct: ClassType, args: Seq[Expr]) extends Expr with CachingTyped {
    protected def computeType(using Symbols): Type = ct.lookupClass match {
      case Some(tcd) => checkParamTypes(args.map(_.getType), tcd.fields.map(_.tpe), ct)
      case _ => Untyped
    }
  }

  /** $encodingof `expr.selector` */
  case class ClassSelector(expr: Expr, selector: Identifier) extends Expr with CachingTyped {
    def field(using Symbols): Option[ValDef] = getClassType(expr) match {
      case ct: ClassType => ct.getField(selector)
      case _ => None
    }

    protected def computeType(using s: Symbols): Type = expr.getType match {
      case ct: ClassType =>
        field.map(_.tpe).orElse((s.lookupFunction(selector), s.lookupClass(ct.id, ct.tps)) match {
          case (Some(fd), Some(tcd)) =>
            Some(typeOps.instantiateType(fd.returnType, (tcd.cd.tparams.map(_.tp) zip tcd.tps).toMap))
          case _ =>
            None
        }).getOrElse(Untyped)
      case tp =>
        Untyped
    }
  }

  /** $encodingof `expr.isInstanceOf[tpe]` */
  case class IsInstanceOf(expr: Expr, tpe: Type) extends Expr with CachingTyped {
    override protected def computeType(using s: Symbols): Type = {
      if (s.typesCompatible(expr.getType, tpe)) BooleanType() else Untyped
    }
  }

  /** $encodingof `expr.asInstanceOf[tpe]` */
  case class AsInstanceOf(expr: Expr, tpe: Type) extends Expr with CachingTyped {
    override protected def computeType(using s: Symbols): Type = {
      if (s.typesCompatible(expr.getType, tpe)) tpe.getType else Untyped
    }
  }

  /* ========================================
   *              PATTERNS
   * ======================================== */

  /* Pattern encoding `case binder @ ct(subPatterns...) =>`
   *
   * If [[binder]] is empty, consider a wildcard `_` in its place.
   */
  case class ClassPattern(binder: Option[ValDef], tpe: ClassType, subPatterns: Seq[Pattern]) extends Pattern

  /** Pattern encoding `case binder: ct`
   *      *
   *          * If [[binder]] is empty, consider a wildcard `_` in its place.
   *              */
  case class InstanceOfPattern(binder: Option[ValDef], tpe: Type) extends Pattern {
    val subPatterns = Seq()
  }


  /* ========================================
   *                 TYPES
   * ======================================== */

  protected def getField(tpe: Type, selector: Identifier)(using Symbols): Option[ValDef] =
    tpe match {
      case ct: ClassType => ct.getField(selector)
      case _ => None
    }

  /** Type associated to instances of [[ClassConstructor]] */
  case class ClassType(id: Identifier, tps: Seq[Type]) extends Type {
    def lookupClass(using s: Symbols): Option[TypedClassDef] = s.lookupClass(id, tps)
    def tcd(using s: Symbols): TypedClassDef = s.getClass(id, tps)

    def getField(selector: Identifier)(using s: Symbols): Option[ValDef] = {
      def rec(tcd: TypedClassDef): Option[ValDef] =
        tcd.fields.collectFirst { case vd @ ValDef(`selector`, _, _) => vd }
          .orElse(tcd.parents.view.reverse.flatMap(rec).headOption)
      lookupClass.flatMap(rec)
    }

    def getTypeMember(selector: Identifier)(using Symbols): Option[TypeDef] = {
      def rec(tcd: TypedClassDef): Option[TypeDef] = {
        tcd.typeMembers.find(_.id.name == selector.name)
          .orElse(tcd.parents.view.reverse.flatMap(rec).headOption)
      }
      lookupClass.flatMap(rec)
    }
  }

  /** Top of the typing lattice, corresponds to scala's `Any` type. */
  case class AnyType() extends Type

  /** Bottom of the typing lattice, corresponds to scala's `Nothing` type. */
  case class NothingType() extends Type

  /** Stands for a type we cannot express. */
  case class UnknownType(isPure: Boolean) extends Type

  /** $encodingof `_ :> lo <: hi` */
  case class TypeBounds(lo: Type, hi: Type, flags: Seq[Flag]) extends Type

  /** $encodingof `expr.Type` */
  case class TypeSelect(expr: Option[Expr], selector: Identifier) extends Type {
    val isPathDependent = expr.isDefined

    def lookupTypeDef(using s: Symbols): Option[TypeDef] = expr match {
      case None => s.lookupTypeDef(selector)
      case Some(expr) => expr.getType match {
        case ct: ClassType =>
          ct.getTypeMember(selector)
        case other =>
          sys.error(s"Cannot select type on type $other (${other.getClass})")
      }
    }

    def getTypeDef(using Symbols): TypeDef = lookupTypeDef.get
  }

  /** $encodingof `expr.Type[A, B, ...]` */
  case class TypeApply(selector: TypeSelect, tps: Seq[Type]) extends Type {
    override protected def computeType(using Symbols): Type = {
      if (!wellKinded) Untyped
      else if (applied.isAbstract) this
      else resolve.getType
    }

    def wellKinded(using Symbols): Boolean =
      lookupTypeDef.exists(_.tparams.length == tps.length)

    val isPathDependent = selector.isPathDependent
    def isAbstract(using Symbols) = getTypeDef.isAbstract

    def bounds(using Symbols): TypeBounds = applied.bounds
    def lowerBound(using Symbols): Type = bounds.lo
    def upperBound(using Symbols): Type = bounds.hi

    def lookupTypeDef(using Symbols): Option[TypeDef] = selector.lookupTypeDef
    def getTypeDef(using Symbols): TypeDef = lookupTypeDef.get

    def applied(using Symbols): AppliedTypeDef = getTypeDef.typed(tps)
    def resolve(using Symbols): Type = applied.resolve
  }

  protected def widenTypeParameter(tpe: Typed)(using Symbols): Type = tpe.getType match {
    case tp: TypeParameter => widenTypeParameter(tp.upperBound)
    case ta: TypeApply => widenTypeParameter(ta.upperBound)
    case tpe => tpe
  }

  protected def getClassType(tpe: Typed, tpes: Typed*)(using Symbols): Type =
    widenTypeParameter(tpe.getType) match {
      case ct: ClassType => checkAllTypes(tpes, ct, ct)
      case _ => Untyped
    }

  override protected def getBVType(tpe: Typed, tpes: Typed*)(using Symbols): Type =
    super.getBVType(widenTypeParameter(tpe), tpes*)

  override protected def getADTType(tpe: Typed, tpes: Typed*)(using Symbols): Type = {
    super.getADTType(widenTypeParameter(tpe), tpes*)
  }

  override protected def getTupleType(tpe: Typed, tpes: Typed*)(using Symbols): Type =
    super.getTupleType(widenTypeParameter(tpe), tpes*)

  override protected def getSetType(tpe: Typed, tpes: Typed*)(using Symbols): Type =
    super.getSetType(widenTypeParameter(tpe), tpes*)

  override protected def getBagType(tpe: Typed, tpes: Typed*)(using Symbols): Type =
    super.getBagType(widenTypeParameter(tpe), tpes*)

  override protected def getMapType(tpe: Typed, tpes: Typed*)(using s: Symbols): Type =
    widenTypeParameter(s.leastUpperBound(tpe +: tpes map (_.getType))) match {
      case mt: MapType => mt
      case _ => Untyped
    }

  override protected def getArrayType(tpe: Typed, tpes: Typed*)(using Symbols): Type =
    super.getArrayType(widenTypeParameter(tpe), tpes*)


  /* ========================================
   *              EXTRACTORS
   * ======================================== */

  override def getDeconstructor(that: inox.ast.Trees): inox.ast.TreeDeconstructor { val s: self.type; val t: that.type } = that match {
    case tree: (Trees & that.type) => // The `& that.type` trick allows to convince scala that `tree` and `that` are actually equal...
      class DeconstructorImpl(override val s: self.type, override val t: tree.type & that.type) extends ConcreteTreeDeconstructor(s, t)
      new DeconstructorImpl(self, tree)

    case _ => super.getDeconstructor(that)
  }


  /* ========================================
   *            TREE TRANSFORMERS
   * ======================================== */

  trait OOSelfTreeTransformer extends TreeTransformer with StainlessSelfTreeTransformer

  trait OOSelfTreeTraverser extends TreeTraverser with StainlessSelfTreeTraverser

  class ConcreteOOSelfTreeTransformer(override val s: self.type, override val t: self.type)
    extends OOSelfTreeTransformer {
    def this() = this(self, self)
  }

  class ConcreteOOSelfTreeTraverser(override val trees: self.type)
    extends OOSelfTreeTraverser {
    def this() = this(self)
  }
}

trait Printer extends innerfuns.Printer {
  protected val trees: Trees
  import trees._

  protected def withSymbols[T <: Tree](elems: Seq[Either[T, Identifier]], header: String)
                                      (using PrinterContext): Unit = {
    new StringContext("" +: (List.fill(elems.size - 1)("\n\n") :+ "")*).p((for (e <- elems) yield e match {
      case Left(d) => d
      case Right(id) => PrintWrapper {
        p"<unknown> $header $id"
      }
    })*)
  }

  protected def functions(funs: Seq[Identifier]): PrintWrapper = PrintWrapper {
    pctx ?=>
      withSymbols(funs.map(id => pctx.opts.symbols.flatMap(_.lookupFunction(id)) match {
        case Some(cd) => Left(cd)
        case None => Right(id)
      }), "def")
  }

  protected def typeDefs(tps: Seq[Identifier]): PrintWrapper = PrintWrapper {
    pctx ?=>
      withSymbols(tps.map(id => pctx.opts.symbols.flatMap(_.lookupTypeDef(id)) match {
        case Some(td) => Left(td)
        case None => Right(id)
      }), "type")
  }

  override protected def ppBody(tree: Tree)(using ctx: PrinterContext): Unit = tree match {

    case cd: ClassDef =>
      for (an <- cd.flags) {
        p"""|@${an.asString(using ctx.opts)}
            |"""
      }

      if (cd.isSealed) p"sealed "
      if (cd.isAbstract) p"abstract " else p"case "
      p"class ${cd.id}"
      p"${nary(cd.tparams, ", ", "[", "]")}"
      if (cd.fields.nonEmpty) p"(${cd.fields})"

      if (cd.parents.nonEmpty) {
        p" extends ${nary(cd.parents, " with ")}"
      }

    case td: TypeDef =>
      for (an <- td.flags if an.name != "bounds") {
        p"""|@${an.asString(using ctx.opts)}
            |"""
      }

      if (td.isAbstract) p"abstract "
      p"type ${td.id}${nary(td.tparams, ", ", "[", "]")}"

      if (td.isAbstract) {
        val TypeBounds(lo, hi, _) = td.bounds
        if (lo != NothingType()) p" >: $lo"
        if (hi != AnyType()) p" <: $hi"
      } else {
        p" = ${td.rhs}"
      }

    case ClassType(id, tps) =>
      p"${id}${nary(tps, ", ", "[", "]")}"

    case AnyType() =>
      p"Any"

    case NothingType() =>
      p"Nothing"

    case UnknownType(isPure) =>
      p"?"
      if (isPure) p"@pure"

    case TypeBounds(lo, hi, _) =>
      p"_ >: $lo <: $hi"

    case TypeSelect(None, id) =>
      p"$id"

    case TypeSelect(Some(expr), id) =>
      p"$expr.$id"

    case TypeApply(selector, tps) =>
      p"${selector}${nary(tps, ", ", "[", "]")}"

    case tpd: TypeParameterDef =>
      tpd.tp.flags collectFirst { case Variance(v) => v } foreach (if (_) p"+" else p"-")
      p"${tpd.tp}"
      tpd.tp.flags collectFirst { case Bounds(lo, hi) => (lo, hi) } foreach { case (lo, hi) =>
        if (lo != NothingType()) p" >: $lo"
        if (hi != AnyType()) p" <: $hi"
      }

    case TypeParameter(id, flags) =>
      p"$id"
      for (f <- flags if f.name != "variance" && f.name != "bounds") p" @${f.asString(using ctx.opts)}"

    case ClassConstructor(ct, args) =>
      p"$ct($args)"

    case ClassSelector(cls, selector) =>
      p"$cls.$selector"

    case IsInstanceOf(e, tpe) =>
      p"$e.isInstanceOf[$tpe]"

    case AsInstanceOf(e, tpe) =>
      p"$e.asInstanceOf[$tpe]"

    case ClassPattern(ob, ct, subs) =>
      ob foreach (vd => p"${vd.toVariable} @ ")
      printNameWithPath(ct.id) // no type parameters in patterns
      p"($subs)"

    case InstanceOfPattern(ovd, tpe) =>
      p"${ovd.map(_.toVariable).getOrElse("_")}: $tpe"

    case (tcd: TypedClassDef) =>
      p"typed class ${tcd.id}[${tcd.tps}]"

    case (atd: AppliedTypeDef) =>
      p"type ${atd.td.id}${nary(atd.tps, ", ", "[", "]")}"

    case _ => super.ppBody(tree)
  }

  override protected def requiresParentheses(ex: Tree, within: Option[Tree]): Boolean = (ex, within) match {
    case (_, Some(_: ClassConstructor)) => false
    case _ => super.requiresParentheses(ex, within)
  }
}

class ExprOps(override val trees: Trees) extends innerfuns.ExprOps(trees) {
  import trees._

  protected class TypeFreshener(override val s: trees.type,
                                override val t: trees.type,
                                mapping: Map[TypeParameter, TypeParameter]) extends oo.ConcreteTreeTransformer(s, t) {

    def this(mapping: Map[TypeParameter, TypeParameter]) = this(trees, trees, mapping)

    override def transform(tpe: s.Type): t.Type = tpe match {
      case tp: TypeParameter if mapping contains tp => mapping(tp)
      case _ => super.transform(tpe)
    }
  }

  override def freshenTypeParams(tps: Seq[TypeParameter]): Seq[TypeParameter] = {
    val tpMap = tps.foldLeft(Map[TypeParameter, TypeParameter]()) { case (tpMap, tp) =>
      val freshener = new TypeFreshener(tpMap)
      val freshTp = freshener.transform(tp.freshen).asInstanceOf[TypeParameter]
      tpMap + (tp -> freshTp)
    }

    tps.map(tpMap)
  }

  /* =============================
   * Freshening of local variables
   * ============================= */

  protected class OOFreshener(freshenChooses: Boolean)
    extends InnerFunsFreshener(freshenChooses) {

      override def transformAndGetEnv(pat: Pattern, env: Env): (Pattern, Env) = pat match {

        case ClassPattern(vdOpt, ct, subPatterns) =>
          val freshVdOpt = vdOpt.map(vd => transform(vd.freshen, env))
          val newPatterns = subPatterns.map(transformAndGetEnv(_, env))
          (
            ClassPattern(
              freshVdOpt,
              transform(ct, env).asInstanceOf[ClassType],
              newPatterns.map(_._1)
            ),
            newPatterns.map(_._2).fold
              (env ++ freshVdOpt.map(freshVd => vdOpt.get.id -> freshVd.id))
              (_ ++ _)
          )

        case InstanceOfPattern(vdOpt, tpe) =>
          val freshVdOpt = vdOpt.map(vd => transform(vd.freshen, env))
          val newEnv = env ++ freshVdOpt.map(freshVd => vdOpt.get.id -> freshVd.id)
          (InstanceOfPattern(freshVdOpt, transform(tpe, env)), newEnv)

        case _ => super.transformAndGetEnv(pat, env)
      }
  }

  override def freshenLocals(expr: Expr, freshenChooses: Boolean = false): Expr = {
    new OOFreshener(freshenChooses).transform(expr, Map.empty[Identifier, Identifier])
  }
}

trait TreeDeconstructor extends innerfuns.TreeDeconstructor {
  protected val s: Trees
  protected val t: Trees

  override def deconstruct(e: s.Expr): Deconstructed[t.Expr] = e match {
    case s.ClassConstructor(ct, args) =>
      (Seq(), Seq(), args, Seq(ct), Seq(), (_, _, es, tps, _) => t.ClassConstructor(tps.head.asInstanceOf[t.ClassType], es))

    case s.ClassSelector(expr, selector) =>
      (Seq(selector), Seq(), Seq(expr), Seq(), Seq(), (ids, _, es, _, _) => t.ClassSelector(es.head, ids.head))

    case s.IsInstanceOf(e, tpe) =>
      (Seq(), Seq(), Seq(e), Seq(tpe), Seq(), (_, _, es, tps, _) => t.IsInstanceOf(es.head, tps.head))

    case s.AsInstanceOf(e, tpe) =>
      (Seq(), Seq(), Seq(e), Seq(tpe), Seq(), (_, _, es, tps, _) => t.AsInstanceOf(es.head, tps.head))

    case _ => super.deconstruct(e)
  }

  override def deconstruct(pattern: s.Pattern): DeconstructedPattern = pattern match {
    case s.ClassPattern(binder, ct, subs) =>
      (Seq(), binder.map(_.toVariable).toSeq, Seq(), Seq(ct), subs, (_, vs, _, tps, subs) => {
        t.ClassPattern(vs.headOption.map(_.toVal), tps.head.asInstanceOf[t.ClassType], subs)
      })
    case s.InstanceOfPattern(binder, ct) =>
      (Seq(), binder.map(_.toVariable).toSeq, Seq(), Seq(ct), Seq(), (_, vs, _, tps, _) => {
        t.InstanceOfPattern(vs.headOption.map(_.toVal), tps.head)
      })
    case _ => super.deconstruct(pattern)
  }

  override def deconstruct(tpe: s.Type): Deconstructed[t.Type] = tpe match {
    case s.ClassType(id, tps) => (Seq(id), Seq(), Seq(), tps, Seq(), (ids, _, _, tps, _) => t.ClassType(ids.head, tps))
    case s.AnyType() => (Seq(), Seq(), Seq(), Seq(), Seq(), (_, _, _, _, _) => t.AnyType())
    case s.NothingType() => (Seq(), Seq(), Seq(), Seq(), Seq(), (_, _, _, _, _) => t.NothingType())
    case s.UnknownType(pure) => (Seq(), Seq(), Seq(), Seq(), Seq(), (_, _, _, _, _) => t.UnknownType(pure))
    case s.TypeBounds(lo, hi, fs) => (Seq(), Seq(), Seq(), Seq(lo, hi), fs, (_, _, _, tps, fs) => t.TypeBounds(tps(0), tps(1), fs))

    case s.TypeSelect(expr, id) => (Seq(id), Seq(), expr.toSeq, Seq(), Seq(), (ids, _, exprs, _, _) =>
      t.TypeSelect(exprs.headOption, ids(0)))

    case s.TypeApply(sel, tps) => (Seq(), Seq(), Seq(), sel +: tps, Seq(), (_, _, _, tps, _) =>
      t.TypeApply(tps.head.asInstanceOf[t.TypeSelect], tps.tail))

    case _ => super.deconstruct(tpe)
  }

  override def deconstruct(f: s.Flag): DeconstructedFlag = f match {
    case s.IsCaseObject => (Seq(), Seq(), Seq(), (_, _, _) => t.IsCaseObject)
    case s.IsInvariant => (Seq(), Seq(), Seq(), (_, _, _) => t.IsInvariant)
    case s.IsAbstract => (Seq(), Seq(), Seq() ,(_, _, _) => t.IsAbstract)
    case s.IsSealed => (Seq(), Seq(), Seq(), (_, _, _) => t.IsSealed)
    case s.Bounds(lo, hi) => (Seq(), Seq(), Seq(lo, hi), (_, _, tps) => t.Bounds(tps(0), tps(1)))
    case s.Variance(v) => (Seq(), Seq(), Seq(), (_, _, _) => t.Variance(v))
    case s.IsTypeMemberOf(id) => (Seq(id), Seq(), Seq(), (ids, _, _) => t.IsTypeMemberOf(ids(0)))
    case _ => super.deconstruct(f)
  }
}

class ConcreteTreeDeconstructor(override val s: Trees, override val t: Trees) extends TreeDeconstructor

trait DefinitionTransformer extends inox.transformers.DefinitionTransformer with transformers.Transformer {
  val s: Trees
  val t: Trees

  def transform(cd: s.ClassDef): t.ClassDef = {
    val env = initEnv

    new t.ClassDef(
      transform(cd.id, env),
      cd.tparams.map(transform(_, env)),
      cd.parents.map(ct => transform(ct, env).asInstanceOf[t.ClassType]),
      cd.fields.map(transform(_, env)),
      cd.flags.map(transform(_, env))
    ).copiedFrom(cd)
  }

  def transform(td: s.TypeDef): t.TypeDef = {
    val env = initEnv

    new t.TypeDef(
      transform(td.id, env),
      td.tparams.map(transform(_, env)),
      transform(td.rhs, env),
      td.flags.map(transform(_, env))
    ).copiedFrom(td)
  }
}

trait TreeTransformer extends transformers.TreeTransformer with DefinitionTransformer

class ConcreteTreeTransformer(override val s: Trees, override val t: Trees) extends TreeTransformer

trait DefinitionTraverser extends inox.transformers.DefinitionTraverser with transformers.Traverser {
  val trees: Trees

  def traverse(cd: trees.ClassDef): Unit = {
    val env = initEnv

    traverse(cd.id, env)
    cd.tparams.foreach(traverse(_, env))
    cd.parents.foreach(traverse(_, env))
    cd.fields.foreach(traverse(_, env))
    cd.flags.foreach(traverse(_, env))
  }

  def traverse(td: trees.TypeDef): Unit = {
    val env = initEnv

    traverse(td.id, env)
    td.tparams.foreach(traverse(_, env))
    traverse(td.rhs, env)
  }
}

trait TreeTraverser extends transformers.TreeTraverser with DefinitionTraverser

trait SimpleSymbolTransformer extends inox.transformers.SimpleSymbolTransformer {
  val s: Trees
  val t: Trees

  protected def transformClass(cd: s.ClassDef): t.ClassDef
  protected def transformTypeDef(td: s.TypeDef): t.TypeDef

  override def transform(syms: s.Symbols): t.Symbols = super.transform(syms)
    .withClasses(syms.classes.values.toSeq.map(transformClass))
    .withTypeDefs(syms.typeDefs.values.toSeq.map(transformTypeDef))
}

object SymbolTransformer {
  def apply(trans: inox.transformers.DefinitionTransformer {
    val s: Trees
    val t: Trees
  }): inox.transformers.SymbolTransformer {
    val s: trans.s.type
    val t: trans.t.type
  } = {
    class Impl(override val s: trans.s.type, override val t: trans.t.type) extends SimpleSymbolTransformer {
      protected def transformFunction(fd: s.FunDef): t.FunDef = trans.transform(fd)
      protected def transformSort(sort: s.ADTSort): t.ADTSort = trans.transform(sort)
      protected def transformClass(cd: s.ClassDef): t.ClassDef = {
        val env = trans.initEnv

        new t.ClassDef(
          trans.transform(cd.id, env),
          cd.tparams.map(tdef => trans.transform(tdef, env)),
          cd.parents.map(ct => trans.transform(ct, env).asInstanceOf[t.ClassType]),
          cd.fields.map(vd => trans.transform(vd, env)),
          cd.flags.map(f => trans.transform(f, env))
        ).copiedFrom(cd)
      }

      protected def transformTypeDef(td: s.TypeDef): t.TypeDef = {
        val env = trans.initEnv

        new t.TypeDef(
          trans.transform(td.id, env),
          td.tparams.map(tdef => trans.transform(tdef, env)),
          trans.transform(td.rhs, env),
          td.flags.map(f => trans.transform(f, env))
        ).copiedFrom(td)
      }
    }

    new Impl(trans.s, trans.t)
  }
}
