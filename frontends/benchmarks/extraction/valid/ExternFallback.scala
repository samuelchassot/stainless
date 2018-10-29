import stainless.lang._
import stainless.annotation._

import scala.annotation.meta.field

object ExternFallback {

  import scala.collection.concurrent.TrieMap

  @extern
  def getTrieMap: TrieMap[BigInt, String] = TrieMap.empty

  @extern
  def setTrieMap(trie: TrieMap[BigInt, String]): Unit = ()

  def prop = {
    setTrieMap(getTrieMap)
    assert(true)
  }

  case class Wrapper[K, V](
    @(extern @field)
    theMap: TrieMap[K, V]
  ) {
    @extern
    def getMap: TrieMap[K, V] = theMap

    @extern
    def setMap(map: TrieMap[K, V]): Unit = ()
  }

  def prop2 = {
    val wrapper = Wrapper(getTrieMap)
    wrapper.setMap(wrapper.getMap)
    assert(true)
  }
}