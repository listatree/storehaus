package com.twitter.storehaus.redis

import com.twitter.bijection.{ Bijection, Injection }
import com.twitter.finagle.redis.util.{ CBToString, StringToChannelBuffer }
import com.twitter.storehaus.algebra.MergeableStore
import com.twitter.storehaus.testing.CloseableCleanup
import com.twitter.util.{ Await, Future }
import org.jboss.netty.buffer.ChannelBuffer
import org.specs2.mutable._
import scala.util.Try

class RedisSortedSetSpec extends Specification
  with CloseableCleanup[RedisSortedSetStore]
  with DefaultRedisClient {
  import com.twitter.bijection.Bijection._

  implicit def strToCb =
    Bijection.build(StringToChannelBuffer(_: String))(
      CBToString(_: ChannelBuffer))

  val closeable: RedisSortedSetStore =
    RedisSortedSetStore(client)

  val sets: MergeableStore[String, Seq[(String, Double)]] =
    closeable.convert(StringToChannelBuffer(_: String))

  val members: MergeableStore[(String, String), Double] =
    closeable.members.convert {
      case (s,m) => (StringToChannelBuffer(s),
                     StringToChannelBuffer(m))
    }

  val commits = Seq(("sritchie", 137.0), ("softprops", 73.0),
                    ("rubanm", 32.0), ("johnynek", 17.0))

  object ::> {
    def unapply(xs: scala.collection.TraversableLike[_,_]) =
      if (xs.isEmpty) None else Some(xs.init, xs.last)
  }

  sequential // Required as tests mutate the store in order

  "RedisSortedSet" should {
    "support Store operations" in {
      Await.result(for {
        put     <- sets.put(("commits", Some(commits)))
        commits <- sets.get("commits")
      } yield commits) must beSome(commits.sortWith(_._2 < _._2))
    }

    "support merge operations" in {
      val merged = Await.result(for {
        _       <- sets.merge(("commits", Seq(("sritchie", 1.0))))
        commits <- sets.get("commits")
      } yield commits)
      (for (_ ::> last <- merged) yield last) must beSome(
        ("sritchie", 138.0))
    }
    "support delete operation" in {
      Await.result(for {
        _       <- sets.put(("commits", None))
        commits <- sets.get("commits")
      } yield commits) must beNone
    }
  }

  "RedisSortedSet#members" should {

    val putting =
        commits.map { case (m,d) => (("commits", m), Some(d)) }
              .toMap
    "support Store operations" in {
      // TODO(doug) Future.collect should really work with all iterables
      Await.result(Future.collect(members.multiPut(putting).values.toSeq))
      putting.foreach {
        case (k, v) =>
          Await.result(members.get(k)) aka("key %s" format k) must_==(v)
      }
    }
    "support merge operations" in {
      val who = ("commits", "sritchie")
      Await.result(for {
        _     <- members.merge((who, 1.0))
        score <- members.get(who)
      } yield score) aka("score of %s" format who) must beSome(138.0)
    }
    "support delete operation" in {
      val who = ("commits", "sritchie")
      Await.result(for {
        _ <- members.put((who, None))
        score <- members.get(who)
      } yield score) aka("score of %s" format who) must beNone
    }
  }
}
