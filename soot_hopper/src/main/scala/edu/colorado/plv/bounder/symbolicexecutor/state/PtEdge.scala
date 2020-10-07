package edu.colorado.plv.bounder.symbolicexecutor.state

import edu.colorado.plv.bounder.ir.FieldRef

sealed trait HeapPtEdge

case class FieldPtEdge(p:PureVar, fieldName:String) extends HeapPtEdge{
  override def toString:String = s"${p.toString}.${fieldName}"
}
//Note: these can contain
//case class LocalPtEdge(src : StackVar, snk:Val) extends PtEdge

