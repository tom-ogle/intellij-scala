package scala.meta.trees

import com.intellij.psi._
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.plugins.scala.lang.psi.api.base._
import org.jetbrains.plugins.scala.lang.psi.api.base.types._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScTypeAliasDeclaration, ScFunction, ScFunctionDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel._
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager
import org.jetbrains.plugins.scala.lang.psi.types.result.{Failure, Success, TypeResult, TypingContext}
import org.jetbrains.plugins.scala.lang.psi.types.{ScType, Compatibility, ScSubstitutor, ScTypeParameterType}
import org.jetbrains.plugins.scala.lang.psi.{api => p, types => ptype}
import org.jetbrains.plugins.scala.lang.refactoring.util.ScTypeUtil.AliasType
import org.scalameta.collections._

import scala.collection.immutable.Seq
import scala.language.postfixOps
import scala.meta.internal.{ast => m, semantic => h}
import scala.meta.trees.error._
import scala.{Seq => _}

trait TypeAdapter {
  self: TreeConverter =>

  private val typeCache = TwoWayCache[ptype.ScType, m.Type]()
  private val typeElementCache = TwoWayCache[ScTypeElement, m.Type]()
  private val psiElementTypeChache = TwoWayCache[PsiElement, m.Type]()

  def toType(tp: ScTypeElement): m.Type = {
    typeElementCache.getOrElseUpdate(tp, {
      tp match {
        case t: ScSimpleTypeElement =>
          val s = new ScSubstitutor(ScSubstitutor.cache.toMap, Map(), None)
          toType(s.subst(t.calcType))
        case t: ScFunctionalTypeElement =>
          toType(t.paramTypeElement) match {
            case m.Type.Tuple(elements) => m.Type.Function(elements, toType(t.returnTypeElement.get))
            case param => m.Type.Function(Seq(param), toType(t.returnTypeElement.get))
          }
        case t: ScParameterizedTypeElement =>
          m.Type.Apply(toType(t.typeElement.calcType), t.typeArgList.typeArgs.toStream.map(toType))
        case t: ScTupleTypeElement =>
          m.Type.Tuple(Seq(t.components.map(toType): _*))
        case t: ScWildcardTypeElement =>
          m.Type.Placeholder(typeBounds(t))
        case t: ScParenthesisedTypeElement =>
          t.typeElement match {
            case Some(t: ScInfixTypeElement) => m.Type.ApplyInfix(toType(t.lOp), toTypeName(t.ref), toType(t.rOp.get))
            case _ => unreachable
          }
        case t: ScTypeVariableTypeElement => die("i cannot into type variables")
        case t: ScExistentialTypeElement =>
          val clauses = Seq(t.clause.declarations map {
            _ match {
              case tp: ScTypeAliasDeclaration => toTypeDecl(tp)
              case other => other ?!
            }
          } : _*)
          val quantified = toType(t.quantified)
          m.Type.Existential(quantified, clauses)
        case other: ScTypeElement =>
          LOG.warn(s"Using slow type conversion of type element ${other.getClass}: ${other.getText}")
          toType(other.getType())
        case other => other ?!
      }
    })
  }

  def toType(tr: TypeResult[ptype.ScType]): m.Type = {
    import org.jetbrains.plugins.scala.lang.psi.types.result._
    tr match {
      case Success(res, _) => toType(res)
      case Failure(cause, place) => throw new ScalaMetaTypeResultFailure(place, cause)
    }
  }

  def toType(elem: PsiElement): m.Type = {
    psiElementTypeChache.getOrElseUpdate(elem, {
      elem match {
        case t: typedef.ScTemplateDefinition =>
          val s = new ScSubstitutor(ScSubstitutor.cache.toMap, Map(), None)
          toType(s.subst(t.getType(TypingContext.empty).get)) // FIXME: what about typing context?
        case t: packaging.ScPackaging =>
          m.Type.Singleton(toTermName(t.reference.get)).setTypechecked
        case t: ScConstructor =>
          m.Type.Method(toParams(Seq(t.arguments:_*)), toType(t.newTemplate.get.getType(TypingContext.empty))).setTypechecked
        case t: ScPrimaryConstructor =>
          m.Type.Method(Seq(t.parameterList.clauses.map(convertParamClause):_*), toType(t.containingClass)).setTypechecked
        case t: ScFunctionDefinition =>
          m.Type.Method(Seq(t.parameterList.clauses.map(convertParamClause):_*), toType(t.getTypeWithCachedSubst)).setTypechecked
        case t: ScFunction =>
          m.Type.Function(Seq(t.paramTypes.map(toType(_, t).asInstanceOf[m.Type.Arg]): _*), toType(t.returnType)).setTypechecked
        case t: ScParameter =>
          val s = new ScSubstitutor(ScSubstitutor.cache.toMap, Map(), None)
          toType(s.subst(t.typeElement.get.getType().get))
        case t: ScTypedDefinition =>
          t.getTypeWithCachedSubst match {
            case Success(res, place) => toType(res)
            case Failure(cause, place) => unresolved(cause, place)
          }
        case t: ScReferenceElement =>
          t.bind() match {
            case Some(result) => toType(result.element)
            case None => m.Type.Placeholder(m.Type.Bounds(None, None))
          }
        case t: PsiPackage if t.getName == null =>
          m.Type.Singleton(std.rootPackageName).setTypechecked
        case t: PsiPackage =>
          m.Type.Singleton(toTermName(t)).setTypechecked
        case t: PsiClass =>
          m.Type.Name(t.getName).withAttrsFor(t).setTypechecked
        case t: PsiMethod =>
          m.Type.Method(Seq(t.getParameterList.getParameters
            .map(Compatibility.toParameter)
            .map(i=> convertParam(i.paramInCode.get))
            .toStream),
            toType(ScType.create(t.getReturnType, t.getProject))).setTypechecked
        case other => other ?!
      }
    })
  }

  def toType(tp: ptype.ScType, pivot: PsiElement = null): m.Type = {
    typeCache.getOrElseUpdate(tp, {
      tp.isAliasType match {
        case Some(AliasType(ta, lower, upper)) => return toTypeName(ta)
        case _ =>
      }
      tp match {
        case t: ptype.ScParameterizedType =>
          m.Type.Apply(toType(t.designator), Seq(t.typeArgs.map(toType(_)): _*)).setTypechecked
        case t: ptype.ScThisType =>
          toTypeName(t.clazz).setTypechecked
        case t: ptype.ScProjectionType =>
          t.projected match {
            case tt: ptype.ScThisType =>
              m.Type.Select(toTermName(tt.clazz), toTypeName(t.actualElement)).setTypechecked
            case _ =>
              m.Type.Project(toType(t.projected), toTypeName(t.actualElement)).setTypechecked
          }
        case t: ptype.ScDesignatorType =>
          if (t.element.isSingletonType)
            toSingletonType(t.element)
          else
            toTypeName(t.element)
        case t: ptype.ScCompoundType =>
          m.Type.Compound(Seq(t.components.map(toType(_)):_*), Seq.empty)
        case t: ptype.ScExistentialType =>
          val wcards = t.wildcards.map {wc =>
            val ubound = if (wc.upperBound == ptype.Any)      None else Some(toType(wc.upperBound))
            val lbound = if (wc.lowerBound == ptype.Nothing)  None else Some(toType(wc.lowerBound))
            m.Decl.Type(Nil, m.Type.Name(wc.name)
                //FIXME: pass actual prefix, when solution for recursive prefix computation is ready
                .withAttrs(h.Denotation.Single(h.Prefix.Zero, toSymbolWtihParent(wc.name, pivot, h.Signature.Type)))
                .setTypechecked,
              Nil, m.Type.Bounds(lbound, ubound)).setTypechecked
          }
          m.Type.Existential(toType(t.quantified), wcards).setTypechecked
        case t: ptype.StdType =>
          toStdTypeName(t)
        case t: ScTypeParameterType =>
          m.Type.Name(t.name).withAttrsFor(t.param).setTypechecked
        case t: ptype.ScType =>
          LOG.warn(s"Unknown type: ${t.getClass} - ${t.canonicalText}")
          m.Type.Name(t.canonicalText).withAttrs(h.Denotation.Zero)
      }
    })
  }

  def toSingletonType(elem: PsiElement): m.Type.Singleton = {
    m.Type.Singleton(toTermName(elem)).setTypechecked
  }

  def toTypeParams(tp: ScTypeParameterType): m.Type.Param = {
    val ubound = if (tp.upper.v == ptype.Any)      None else Some(toType(tp.upper.v))
    val lbound = if (tp.lower.v == ptype.Nothing)  None else Some(toType(tp.lower.v))
    m.Type.Param(
      if(tp.isCovariant) m.Mod.Covariant() :: Nil else if(tp.isContravariant) m.Mod.Contravariant() :: Nil else Nil,
      if (tp.name != "_") m.Type.Name(tp.name) else m.Name.Anonymous().withAttrs(h.Denotation.Zero).setTypechecked,
      Seq(tp.args.map(toTypeParams):_*),
      m.Type.Bounds(lbound, ubound), Nil, Nil
    )
  }

  def toTypeParams(tp: p.statements.params.ScTypeParam): m.Type.Param = {
    m.Type.Param(
      if(tp.isCovariant) m.Mod.Covariant() :: Nil else if(tp.isContravariant) m.Mod.Contravariant() :: Nil else Nil,
      if (tp.name != "_") toTypeName(tp) else m.Name.Anonymous().withAttrsFor(tp).setTypechecked,
      Seq(tp.typeParameters.map(toTypeParams):_*),
      typeBounds(tp),
      viewBounds(tp),
      contextBounds(tp)
    ).setTypechecked
  }

  def toTypeParams(tp: PsiTypeParameter): m.Type.Param = {
    m.Type.Param(
      m.Mod.Covariant() :: Nil,
      toTypeName(tp),
      Seq(tp.getTypeParameters.map(toTypeParams):_*),
      m.Type.Bounds(None, None),
      Seq.empty, Seq.empty
    ).setTypechecked
  }

  def viewBounds(tp: ScTypeBoundsOwner): Seq[m.Type] = {
    Seq(tp.viewTypeElement.map(toType):_*)
  }

  def contextBounds(tp: ScTypeBoundsOwner): Seq[m.Type] = {
    Seq(tp.contextBoundTypeElement.map(toType):_*)
  }

  def typeBounds(tp: ScTypeBoundsOwner): m.Type.Bounds = {
    m.Type.Bounds(tp.lowerTypeElement.map(toType), tp.upperTypeElement.map(toType)).setTypechecked
  }

  def returnType(tr: ptype.result.TypeResult[ptype.ScType]): m.Type = {
    import ptype.result._
    tr match {
      case Success(t, elem) => toType(t)
      case Failure(cause, place) =>
        LOG.warn(s"Failed to infer return type($cause) at ${place.map(_.getText).getOrElse("UNKNOWN")}")
        m.Type.Name("Unit").setTypechecked
    }
  }


  def fromType(tpe: m.Type): ptype.ScType = {
    typeCache.getOrElseUpdate(tpe, {
      tpe match {
        case n:m.Type.Name =>
          val psi = fromSymbol(n.denot.symbols.head)
          psi match {
            case td: p.toplevel.typedef.ScTemplateDefinition =>
              td.getType(TypingContext.empty).get
            case _ =>
              ???
          }
        case _ =>
          ???
      }
    }
    )
  }
}