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

import org.neo4j.cypher.internal.commons.CypherFunSuite

object RewritableTest {
  trait Exp extends Product with Rewritable
  case class Val(int: Int) extends Exp {
    def dup(children: Seq[AnyRef]): this.type =
      Val(children(0).asInstanceOf[Int]).asInstanceOf[this.type]
  }
  case class Add(lhs: Exp, rhs: Exp) extends Exp {
    def dup(children: Seq[AnyRef]): this.type =
      Add(children(0).asInstanceOf[Exp], children(1).asInstanceOf[Exp]).asInstanceOf[this.type]
  }
  case class Sum(args: Seq[Exp]) extends Exp {
    def dup(children: Seq[AnyRef]): this.type =
      Sum(children(0).asInstanceOf[Seq[Exp]]).asInstanceOf[this.type]
  }
  case class Pos(latlng: (Exp, Exp)) extends Exp {
    def dup(children: Seq[AnyRef]): this.type =
      Pos(children(0).asInstanceOf[(Exp, Exp)]).asInstanceOf[this.type]
  }
  case class Options(args: Seq[(Exp, Exp)]) extends Exp {
    def dup(children: Seq[AnyRef]): this.type =
      Options(children(0).asInstanceOf[Seq[(Exp, Exp)]]).asInstanceOf[this.type]
  }
}

class RewritableTest extends CypherFunSuite {
  import RewritableTest._

  test("topDown rewrite should be identical when no rule matches") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val result = ast.rewrite(topDown(Rewriter.lift {
      case None => ???
    }))

    assert(result === ast)
  }

  test("topDown refold should be identical when no rule matches") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(33)(topDown(FoldingRewriter.lift {
      case None => ???
    }))

    assert(result === ast)
    assert(acc === 33)
  }

  test("topDown rewrite should be identical when using identity") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val result = ast.rewrite(topDown(Rewriter.lift {
      case a => a
    }))

    assert(result === ast)
  }

  test("topDown refold should be identical when using identity") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(33)(topDown(FoldingRewriter.lift {
      case a => acc => (a, acc)
    }))

    assert(result === ast)
    assert(acc === 33)
  }

  test("topDown rewrite should match and replace primitives") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val result = ast.rewrite(topDown(Rewriter.lift {
      case _: java.lang.Integer => 99: java.lang.Integer
    }))

    assert(result === Add(Val(99), Add(Val(99), Val(99))))
  }

  test("topDown refold should match and replace primitives") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(7)(topDown(FoldingRewriter.lift {
      case i: java.lang.Integer => acc =>
        (99: java.lang.Integer, acc + i)
    }))

    assert(result === Add(Val(99), Add(Val(99), Val(99))))
    assert(acc === 13)
  }

  test("topDown rewrite should match and replace trees") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val result = ast.rewrite(topDown(Rewriter.lift {
      case Add(Val(x), Val(y)) =>
        Val(x + y)
    }))

    assert(result === Add(Val(1), Val(5)))
  }

  test("topDown refold should match and replace trees") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(9)(topDown(FoldingRewriter.lift {
      case Add(Val(x), Val(y)) => acc =>
        (Val(x + y), acc + x + y)
    }))

    assert(result === Add(Val(1), Val(5)))
    assert(acc === 14)
  }

  test("topDown rewrite should match and replace primitives and trees") {
    val ast = Add(Val(8), Add(Val(2), Val(3)))

    val result = ast.rewrite(topDown(Rewriter.lift {
      case Val(_) =>
        Val(1)
      case Add(Val(x), Val(y)) =>
        Val(x + y)
    }))

    assert(result === Add(Val(1), Val(5)))
  }

  test("topDown refold should match and replace primitives and trees") {
    val ast = Add(Val(8), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(101)(topDown(FoldingRewriter.lift {
      case Val(_) => acc =>
        (Val(1), acc + 1)
      case Add(Val(x), Val(y)) => acc =>
        (Val(x + y), acc + 100)
    }))

    assert(result === Add(Val(1), Val(5)))
    assert(acc === 202)
  }

  test("topDown rewrite should duplicate terms with pair parameters") {
    val ast = Add(Val(1), Pos((Val(2), Val(3))))

    val result = ast.rewrite(topDown(Rewriter.lift {
      case Val(_) => Val(99)
    }))

    assert(result === Add(Val(99), Pos((Val(99), Val(99)))))
  }

  test("topDown refold should duplicate terms with pair parameters") {
    val ast = Add(Val(1), Pos((Val(2), Val(3))))

    val (result, acc) = ast.refold(0)(topDown(FoldingRewriter.lift {
      case Val(_) => acc =>
        (Val(99), acc + 1)
    }))

    assert(result === Add(Val(99), Pos((Val(99), Val(99)))))
    assert(acc === 3)
  }

  test("topDown rewrite should duplicate terms with sequence of pairs") {
    val ast = Add(Val(1), Options(Seq((Val(2), Val(3)), (Val(4), Val(5)))))

    val result = ast.rewrite(topDown(Rewriter.lift {
      case Val(_) => Val(99)
    }))

    assert(result === Add(Val(99), Options(Seq((Val(99), Val(99)), (Val(99), Val(99))))))
  }

  test("topDown refold should duplicate terms with sequence of pairs") {
    val ast = Add(Val(1), Options(Seq((Val(2), Val(3)), (Val(4), Val(5)))))

    val (result, acc) = ast.refold(0)(topDown(FoldingRewriter.lift {
      case Val(_) => acc =>
        (Val(99), acc + 1)
    }))

    assert(result === Add(Val(99), Options(Seq((Val(99), Val(99)), (Val(99), Val(99))))))
    assert(acc === 5)
  }

  test("bottomUp rewrite should be identical when no rule matches") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val result = ast.rewrite(bottomUp(Rewriter.lift {
      case None => ???
    }))

    assert(result === ast)
  }

  test("bottomUp refold should be identical when no rule matches") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(33)(bottomUp(FoldingRewriter.lift {
      case None => ???
    }))

    assert(result === ast)
    assert(acc == 33)
  }

  test("bottomUp rewrite should be identical when using identity") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val result = ast.rewrite(bottomUp(Rewriter.lift {
      case a => a
    }))

    assert(result === ast)
  }

  test("bottomUp refold should be identical when using identity") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(33)(bottomUp(FoldingRewriter.lift {
      case a => acc => (a, acc)
    }))

    assert(result === ast)
    assert(acc === 33)
  }

  test("bottomUp rewrite should match and replace primitives") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val result = ast.rewrite(bottomUp(Rewriter.lift {
      case _: java.lang.Integer => 99: java.lang.Integer
    }))

    assert(result === Add(Val(99), Add(Val(99), Val(99))))
  }

  test("bottomUp refold should match and replace primitives") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(7)(bottomUp(FoldingRewriter.lift {
      case i: java.lang.Integer => acc =>
        (99: java.lang.Integer, acc + i)
    }))

    assert(result === Add(Val(99), Add(Val(99), Val(99))))
    assert(acc === 13)
  }

  test("bottomUp rewrite should match and replace trees") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val result = ast.rewrite(bottomUp(Rewriter.lift {
      case Add(Val(x), Val(y)) =>
        Val(x + y)
    }))

    assert(result === Val(6))
  }

  test("bottomUp refold should match and replace trees") {
    val ast = Add(Val(1), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(9)(bottomUp(FoldingRewriter.lift {
      case Add(Val(x), Val(y)) => acc =>
        (Val(x + y), acc + x + y)
    }))

    assert(result === Val(6))
    assert(acc === 20)
  }

  test("bottomUp rewrite should match and replace primitives and trees") {
    val ast = Add(Val(8), Add(Val(2), Val(3)))

    val result = ast.rewrite(bottomUp(Rewriter.lift {
      case Val(_) =>
        Val(1)
      case Add(Val(x), Val(y)) =>
        Val(x + y)
    }))

    assert(result === Val(3))
  }

  test("bottomUp refold should match and replace primitives and trees") {
    val ast = Add(Val(8), Add(Val(2), Val(3)))

    val (result, acc) = ast.refold(101)(bottomUp(FoldingRewriter.lift {
      case Val(_) => acc =>
        (Val(1), acc + 1)
      case Add(Val(x), Val(y)) => acc =>
        (Val(x + y), acc + 100)
    }))

    assert(result === Val(3))
    assert(acc === 304)
  }

  test("bottomUp rewrite should duplicate terms with pair parameters") {
    val ast = Add(Val(1), Pos((Val(2), Val(3))))

    val result = ast.rewrite(bottomUp(Rewriter.lift {
      case Val(_) => Val(99)
    }))

    assert(result === Add(Val(99), Pos((Val(99), Val(99)))))
  }

  test("bottomUp refold should duplicate terms with pair parameters") {
    val ast = Add(Val(1), Pos((Val(2), Val(3))))

    val (result, acc) = ast.refold(0)(bottomUp(FoldingRewriter.lift {
      case Val(_) => acc =>
        (Val(99), acc + 1)
    }))

    assert(result === Add(Val(99), Pos((Val(99), Val(99)))))
    assert(acc === 3)
  }

  test("bottomUp rewrite should duplicate terms with sequence of pairs") {
    val ast = Add(Val(1), Options(Seq((Val(2), Val(3)), (Val(4), Val(5)))))

    val result = ast.rewrite(bottomUp(Rewriter.lift {
      case Val(_) => Val(99)
    }))

    assert(result === Add(Val(99), Options(Seq((Val(99), Val(99)), (Val(99), Val(99))))))
  }

  test("bottomUp refold should duplicate terms with sequence of pairs") {
    val ast = Add(Val(1), Options(Seq((Val(2), Val(3)), (Val(4), Val(5)))))

    val (result, acc) = ast.refold(0)(bottomUp(FoldingRewriter.lift {
      case Val(_) => acc =>
        (Val(99), acc + 1)
    }))

    assert(result === Add(Val(99), Options(Seq((Val(99), Val(99)), (Val(99), Val(99))))))
    assert(acc === 5)
  }
}
