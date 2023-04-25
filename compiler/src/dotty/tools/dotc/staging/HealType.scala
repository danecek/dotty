package dotty.tools.dotc
package staging

import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.staging.StagingLevel.*
import dotty.tools.dotc.staging.QuoteTypeTags.*
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.typer.Implicits.SearchFailureType
import dotty.tools.dotc.util.SrcPos

class HealType(pos: SrcPos)(using Context) extends TypeMap {

  /** If the type refers to a locally defined symbol (either directly, or in a pickled type),
   *  check that its staging level matches the current level.
   *  - Static types and term are allowed at any level.
   *  - If a type reference is used a higher level, then it is inconsistent.
   *    Will attempt to heal before failing.
   *  - If a term reference is used a higher level, then it is inconsistent.
   *    It cannot be healed because the term will not exist in any future stage.
   *
   *  If `T` is a reference to a type at the wrong level, try to heal it by replacing it with
   *  a type tag of type `quoted.Type[T]`.
   *  The tag is generated by an instance of `QuoteTypeTags` directly if the splice is explicit
   *  or indirectly by `tryHeal`.
   */
  def apply(tp: Type): Type =
    tp match
      case NonSpliceAlias(aliased) => this.apply(aliased)
      case tp: TypeRef => healTypeRef(tp)
      case tp: TermRef =>
        val inconsistentRoot = levelInconsistentRootOfPath(tp)
        if inconsistentRoot.exists then levelError(inconsistentRoot, tp, pos)
        else tp
      case tp: AnnotatedType =>
        derivedAnnotatedType(tp, apply(tp.parent), tp.annot)
      case _ =>
        mapOver(tp)

  private def healTypeRef(tp: TypeRef): Type =
    tp.prefix match
      case NoPrefix if tp.typeSymbol.hasAnnotation(defn.QuotedRuntime_SplicedTypeAnnot) =>
        tp
      case prefix: TermRef if tp.symbol.isTypeSplice =>
        checkNotWildcardSplice(tp)
        if level == 0 then tp else getQuoteTypeTags.getTagRef(prefix)
      case _: NamedType | _: ThisType | NoPrefix =>
        if levelInconsistentRootOfPath(tp).exists then
          tryHeal(tp)
        else
          tp
      case _ =>
        mapOver(tp)

  private object NonSpliceAlias:
    def unapply(tp: TypeRef)(using Context): Option[Type] = tp.underlying match
      case TypeAlias(alias) if !tp.symbol.isTypeSplice && !tp.typeSymbol.hasAnnotation(defn.QuotedRuntime_SplicedTypeAnnot) => Some(alias)
      case _ => None

  private def checkNotWildcardSplice(splice: TypeRef): Unit =
    splice.prefix.termSymbol.info.argInfos match
      case (tb: TypeBounds) :: _ => report.error(em"Cannot splice $splice because it is a wildcard type", pos)
      case _ =>

  /** Return the root of this path if it is a variable defined in a previous level.
   *  If the path is consistent, return NoSymbol.
   */
  private def levelInconsistentRootOfPath(tp: Type)(using Context): Symbol =
    tp match
      case tp @ NamedType(NoPrefix, _) if level > levelOf(tp.symbol) => tp.symbol
      case tp: NamedType if !tp.symbol.isStatic => levelInconsistentRootOfPath(tp.prefix)
      case tp: ThisType if level > levelOf(tp.cls) => tp.cls
      case _ => NoSymbol

  /** Try to heal reference to type `T` used in a higher level than its definition.
   *  Returns a reference to a type tag generated by `QuoteTypeTags` that contains a
   *  reference to a type alias containing the equivalent of `${summon[quoted.Type[T]]}`.
   *  Emits an error if `T` cannot be healed and returns `T`.
   */
  protected def tryHeal(tp: TypeRef): Type = {
    val reqType = defn.QuotedTypeClass.typeRef.appliedTo(tp)
    val tag = ctx.typer.inferImplicitArg(reqType, pos.span)
    tag.tpe match
      case tp: TermRef =>
        ctx.typer.checkStable(tp, pos, "type witness")
        if levelOf(tp.symbol) > 0 then tp.select(tpnme.Underlying)
        else getQuoteTypeTags.getTagRef(tp)
      case _: SearchFailureType =>
        report.error(
          ctx.typer.missingArgMsg(tag, reqType, "")
            .prepend(i"Reference to $tp within quotes requires a given $reqType in scope.\n")
            .append("\n"),
            pos)
        tp
      case _ =>
        report.error(em"""Reference to $tp within quotes requires a given $reqType in scope.
                      |
                      |""", pos)
        tp
  }

  private def levelError(sym: Symbol, tp: Type, pos: SrcPos): tp.type = {
    report.error(
      em"""access to $sym from wrong staging level:
          | - the definition is at level ${levelOf(sym)},
          | - but the access is at level $level""", pos)
    tp
  }
}
