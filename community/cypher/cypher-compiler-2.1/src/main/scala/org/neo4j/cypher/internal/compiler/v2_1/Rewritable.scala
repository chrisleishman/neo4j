/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1

import java.lang.reflect.Method
import scala.collection.mutable.{HashMap => MutableHashMap}

object Rewriter {
  def lift(f: PartialFunction[AnyRef, AnyRef]): Rewriter = f.lift

  def noop = Rewriter.lift(Map.empty)
}

object FoldingRewriter {
  def lift[R](f: PartialFunction[AnyRef, R => (AnyRef, R)]): FoldingRewriter[R] = f.lift
}

object Rewritable {
  implicit class IteratorEq[A <: AnyRef](val iterator: Iterator[A]) {
    def eqElements[B <: AnyRef](that: Iterator[B]): Boolean = {
      while (iterator.hasNext && that.hasNext) {
        if (!(iterator.next eq that.next))
          return false
      }
      !iterator.hasNext && !that.hasNext
    }
  }

  implicit class DuplicatableAny(val that: AnyRef) extends AnyVal {
    import Foldable._

    def dup(children: Seq[AnyRef]): AnyRef = that match {
      case a: Rewritable =>
        a.dup(children)
      case p: Product =>
        if (children.iterator eqElements p.children)
          p
        else
          p.copyConstructor.invoke(p, children: _*)
      case s: IndexedSeq[_] =>
        children.toIndexedSeq
      case s: Seq[_] =>
        children
      case t =>
        t
    }
  }

  private val productCopyConstructors = new ThreadLocal[MutableHashMap[Class[_], Method]]() {
    override def initialValue: MutableHashMap[Class[_], Method] = new MutableHashMap[Class[_], Method]
  }

  implicit class DuplicatableProduct(val product: Product) extends AnyVal {
    import Foldable._

    def dup(children: Seq[AnyRef]): Product = product match {
      case a: Rewritable =>
        a.dup(children)
      case _ =>
        if (children.iterator eqElements product.children)
          product
        else
          copyConstructor.invoke(product, children: _*).asInstanceOf[Product]
    }

    def copyConstructor: Method = {
      val productClass = product.getClass
      productCopyConstructors.get.getOrElseUpdate(productClass, productClass.getMethods.find(_.getName == "copy").get)
    }
  }

  implicit class RewritableAny(val that: AnyRef) extends AnyVal {
    def refold[R](acc: R)(rewriter: FoldingRewriter[R]): (AnyRef, R) = rewriter.apply(that).fold((that, acc))(_(acc))
    def rewrite(rewriter: Rewriter): AnyRef = rewriter.apply(that).getOrElse(that)
  }
}

trait Rewritable {
  def dup(children: Seq[AnyRef]): this.type
}

object inSequence {
  import Rewritable._

  class InSequenceRewriter(rewriters: Seq[Rewriter]) extends Rewriter {
    def apply(that: AnyRef): Some[AnyRef] =
      Some(rewriters.foldLeft(that) {
        (t, r) => t.rewrite(r)
      })
  }

  def apply(rewriters: Rewriter*) = new InSequenceRewriter(rewriters)

  class InSequenceFoldingRewriter[R](rewriters: Seq[FoldingRewriter[R]]) extends FoldingRewriter[R] {
    def apply(that: AnyRef): Some[R => (AnyRef, R)] = Some(acc =>
      rewriters.foldLeft((that, acc)) {
        case ((t, a), r) => t.refold(a)(r)
      }
    )
  }

  def apply[R](rewriters: FoldingRewriter[R]*) = new InSequenceFoldingRewriter[R](rewriters)
}

object noFolding {
  def apply[R](rewriter: Rewriter): FoldingRewriter[R] = new FoldingRewriter[R] {
    def apply(that: AnyRef): Option[R => (AnyRef, R)] =
      rewriter.apply(that).map(result => s => (result, s))
  }
}

object topDown {
  import Foldable._
  import Rewritable._

  class TopDownRewriter(rewriter: Rewriter) extends Rewriter {
    def apply(that: AnyRef): Some[AnyRef] = {
      val rewrittenThat = that.rewrite(rewriter)
      Some(rewrittenThat.dup(rewrittenThat.children.map(t => this.apply(t).get).toList))
    }
  }

  def apply(rewriter: Rewriter) = new TopDownRewriter(rewriter)

  class TopDownFoldingRewriter[R](rewriter: FoldingRewriter[R]) extends FoldingRewriter[R] {
    def apply(that: AnyRef): Some[R => (AnyRef, R)] = Some(acc => {
      val (rewrittenThat, acc1) = that.refold(acc)(rewriter)
      val (rewrittenChildren, acc2) = rewrittenThat.children.foldLeft((Vector.empty: Vector[AnyRef], acc1)) {
        case ((rewrittenChildren, a), c) =>
          val (rewrittenChild, aa) = this.apply(c).get(a)
          (rewrittenChildren :+ rewrittenChild, aa)
      }
      (rewrittenThat.dup(rewrittenChildren), acc2)
    })
  }

  def apply[R](rewriter: FoldingRewriter[R]) = new TopDownFoldingRewriter[R](rewriter)
}

object bottomUp {
  import Foldable._
  import Rewritable._

  class BottomUpRewriter(rewriter: Rewriter) extends Rewriter {
    def apply(that: AnyRef): Some[AnyRef] = {
      val rewrittenThat = that.dup(that.children.map(t => this.apply(t).get).toList)
      Some(rewrittenThat.rewrite(rewriter))
    }
  }

  def apply(rewriter: Rewriter) = new BottomUpRewriter(rewriter)

  class BottomUpFoldingRewriter[R](rewriter: FoldingRewriter[R]) extends FoldingRewriter[R] {
    def apply(that: AnyRef): Some[R => (AnyRef, R)] = Some(acc => {
      val (rewrittenChildren, acc1) = that.children.foldLeft((Vector.empty: Vector[AnyRef], acc)) {
        case ((rewrittenChildren, a), c) =>
          val (rewrittenChild, aa) = this.apply(c).get(a)
          (rewrittenChildren :+ rewrittenChild, aa)
      }
      val rewrittenThat = that.dup(rewrittenChildren)
      rewrittenThat.refold(acc1)(rewriter)
    })
  }

  def apply[R](rewriter: FoldingRewriter[R]) = new BottomUpFoldingRewriter[R](rewriter)
}
