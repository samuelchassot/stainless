/* Copyright 2009-2021 EPFL, Lausanne */

import stainless.lang._
import stainless.annotation._

object Extern2 {
  @extern
  def plop(a: BigInt): BigInt = {
    // Does not satisfy the postcondition, but works as intended
    // (@extern functions bodies are not extracted, only their contracts).
    a + scala.math.abs(-3)
 }.ensuring {
    _ > 0
  }

  def test(b: BigInt): BigInt = {
    plop(if (b <= 0) -b+1 else b)
 }.ensuring {
    _ > 0
  }

  def test2 = test(42)
  def test3 = test(-2)
}
