package edu.colorado.plv.bounder.ir
import edu.colorado.plv.bounder.lifestate.LifeState.Signature
import edu.colorado.plv.bounder.symbolicexecutor.RelevanceRelation
import upickle.default.{read, write}
import upickle.default.{macroRW, ReadWriter => RW}

import scala.util.matching.Regex

/**
 * A source code location
 * TODO: find a way to make the type system enforce locations are not used cross wrapper implementations
 */
sealed trait Loc{
  //TODO: containing method is inconsistent should also return methods for internal methods and callbacks
  def containingMethod:Option[MethodLoc] = this match{
    case AppLoc(method, _,_) => Some(method)
    case _ => None
  }
  def msgSig : Option[String]
  def toString:String
  def isEntry:Option[Boolean]
  def iSerialized:String
  def serialized:String = iSerialized
}
object Loc{
  implicit val rw:RW[Loc] = RW.merge(macroRW[CallbackMethodInvoke], macroRW[CallinMethodReturn],
    AppLoc.rw, macroRW[CallbackMethodReturn], macroRW[InternalMethodReturn], macroRW[CallinMethodInvoke],
    macroRW[InternalMethodInvoke], GroupedCallinMethodReturn.rw, GroupedCallinMethodInvoke.rw,
    SkippedInternalMethodInvoke.rw,SkippedInternalMethodReturn.rw
  )
}

/**
 * A method definition overridden by the IR wrapper implementation
 */
trait MethodLoc {
  def getLocals():Set[(String,String)]
  def isNative():Boolean

  def getSignature:Signature = Signature(classType,simpleName)
  def simpleName: String
  def classType: String
  def argTypes: List[String]
  def bodyToString: String

  /**
   * No return since call site has that info
   * None for reciever if static
   * @return list of args, [reciever, arg1,arg2 ...]
   */
  def getArgs : List[Option[LocalWrapper]]
  def isStatic:Boolean
  def isInterface:Boolean
  def isSynthetic:Boolean
}
object MethodLoc {
  private val rmQuotes: Regex = "^\"(.*)\"$".r
  implicit val rw:RW[MethodLoc] = upickle.default.readwriter[ujson.Value].bimap[MethodLoc](
    x => ujson.Arr(x.simpleName, x.classType, x.argTypes, x.getArgs.map(v => write(v))),
    json => {
      val name = json.arr(0).str
      val clazz = json.arr(1).str
      val args = json.arr(3).arr.map { v =>
        val v0 = ujson.read(v.str)
        if (v0.arr.isEmpty) {
          None
        } else {
          val name = v0(0).obj("name").str
          val t = v0(0).obj("localType").str
          Some(LocalWrapper(name, t))
        }
      }.toList
      SerializedIRMethodLoc(clazz, name, args)
    }
  )
}

trait LineLoc{
  def lineNumber:Int
  def containingMethod:MethodLoc
  def isFirstLocInMethod:Boolean
}
object LineLoc{
  implicit val rw:RW[LineLoc] = upickle.default.readwriter[ujson.Value].bimap[LineLoc](
    x =>
      ujson.Obj("id" -> x.lineNumber, "desc" -> x.toString, "ident" ->  System.identityHashCode(x)),
    json => json match {
      case ujson.Str(v) => SerializedIRLineLoc (0, v,0)
      case ujson.Obj(v) => SerializedIRLineLoc (v("id").num.toInt, v("desc").str, v("ident").num.toInt)
      case v => throw new IllegalArgumentException(s"Cannot parse $v as LineLoc")
    }
  )
}

/**
 * Represents a command location inside the application
 * @param method object representing containing method in app
 * @param line object representing containing line in app
 * @param isPre //TODO: I think this is before/after cmd?
 */
case class AppLoc(method: MethodLoc, line: LineLoc, isPre:Boolean) extends Loc {
  override def toString: String = (if(isPre) "pre-" else "post-" ) + line.toString()
  override def msgSig = Some(s"[Int] ${method.simpleName}")

  override def isEntry: Option[Boolean] = None
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object AppLoc{
  implicit val rw:RW[AppLoc] = macroRW
}

case class CallinMethodReturn(sig:Signature) extends Loc {
  override def toString:String = "[CI Ret] " + sig.methodSignature

  override def msgSig: Option[String] = Some(s"[CI Ret] ${sig.base} ${sig.methodSignature}" )

  override def isEntry: Option[Boolean] = Some(false)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object CallinMethodReturn{
  implicit val rw:RW[CallinMethodReturn] = macroRW
}

case class CallinMethodInvoke(sig: Signature) extends Loc {
  override def toString:String = "[CI Inv] " + sig.methodSignature

  override def msgSig: Option[String] = Some(s"[CI Inv] ${sig.base} ${sig.methodSignature}")

  override def isEntry: Option[Boolean] = Some(true)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object CallinMethodInvoke{
  implicit val rw:RW[CallinMethodInvoke] = macroRW
}

case class GroupedCallinMethodInvoke(targetClasses: Set[String], fmwName:String) extends Loc {
  override def toString:String = "[CI Inv merge] " + fmwName
  override def msgSig: Option[String] = Some(s"[CI Inv] ${targetClasses.head} ${fmwName}")

  override def isEntry: Option[Boolean] = Some(true)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object GroupedCallinMethodInvoke{
  implicit val rw:RW[GroupedCallinMethodInvoke] = macroRW
}

//TODO: could probably merge this with the CallinMethod classes
case class GroupedCallinMethodReturn(targetClasses: Set[String], fmwName:String) extends Loc {
  override def toString:String = "[CI Ret merge] " + fmwName
  override def msgSig: Option[String] = Some(s"[CI Ret] ${targetClasses.head} ${fmwName}")

  override def isEntry: Option[Boolean] = Some(false)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object GroupedCallinMethodReturn{
  implicit val rw:RW[GroupedCallinMethodReturn] = macroRW
}

case class CallbackMethodInvoke(sig:Signature, loc:MethodLoc) extends Loc {
  override def toString:String = s"[CB Inv] ${sig.base} ${sig.methodSignature}"
  override def msgSig: Option[String] = Some(s"[CB Inv] ${sig.base} ${sig.methodSignature}")

  override def isEntry: Option[Boolean] = Some(true)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object CallbackMethodInvoke{
  implicit val rw:RW[CallbackMethodInvoke] = macroRW
}

// post state of return on callback
case class CallbackMethodReturn(sig:Signature, loc:MethodLoc, line:Option[LineLoc]) extends Loc {
  if( line.isDefined && !line.get.isInstanceOf[SerializedIRLineLoc])
    assert(line.get.containingMethod == loc)
  override def toString:String = s"[CB Ret] ${sig.base} ${sig.methodSignature}"
  override def msgSig: Option[String] = Some(s"")

  override def isEntry: Option[Boolean] = Some(false)
  override def containingMethod:Option[MethodLoc] = Some(loc)
//    line.map(_.containingMethod)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object CallbackMethodReturn{
  implicit val rw:RW[CallbackMethodReturn] = macroRW
}

case class InternalMethodInvoke(clazz:String, name:String, loc:MethodLoc) extends Loc {
  override def msgSig: Option[String] = None

  override def isEntry: Option[Boolean] = Some(true)
  override def containingMethod:Option[MethodLoc] = Some(loc)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object InternalMethodInvoke{
  implicit val rw:RW[InternalMethodInvoke] = macroRW
}

case class InternalMethodReturn(clazz:String, name:String, loc:MethodLoc) extends Loc {
  override def msgSig: Option[String] = None

  override def isEntry: Option[Boolean] = Some(false)
  override def containingMethod:Option[MethodLoc] = Some(loc)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object InternalMethodReturn{
  implicit val rw:RW[InternalMethodReturn] = macroRW
}

case class SkippedInternalMethodInvoke(clazz:String, name:String, loc:MethodLoc) extends Loc{
  override def msgSig: Option[String] = None
  override def isEntry: Option[Boolean] = Some(true)
  override def containingMethod:Option[MethodLoc] = Some(loc)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object SkippedInternalMethodInvoke{
  implicit val rw:RW[SkippedInternalMethodInvoke] = macroRW
}

case class SkippedInternalMethodReturn(clazz:String, name:String, rel:RelevanceRelation, loc:MethodLoc) extends Loc{
  override def msgSig: Option[String] = None
  override def isEntry: Option[Boolean] = Some(false)
  override def containingMethod:Option[MethodLoc] = Some(loc)
  private lazy val iser= write(this)
  override def iSerialized: String = iser
}
object SkippedInternalMethodReturn{
  implicit val rw:RW[SkippedInternalMethodReturn] = macroRW
}
