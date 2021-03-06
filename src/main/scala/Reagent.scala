// The core reagent implementation and accompanying combinators

package chemistry

import scala.annotation.tailrec
import java.util.concurrent.locks._
import chemistry.Util.Implicits._

private[chemistry] sealed abstract class BacktrackCommand {
  // what to do when the backtracking command runs out of choices
  // (i.e., hits bottom)
  def bottom[A](waiter: Waiter[A], backoff: Backoff, snoop: => Boolean): Unit
  def isBlock: Boolean
}
private[chemistry] case object Block extends BacktrackCommand {
  def bottom[A](waiter: Waiter[A], backoff: Backoff, snoop: => Boolean): Unit =
    LockSupport.park(waiter)
  def isBlock: Boolean = true
}
private[chemistry] case object Retry extends BacktrackCommand {
  def bottom[A](waiter: Waiter[A], backoff: Backoff, snoop: => Boolean): Unit =
    backoff.once(waiter.isActive && !snoop, 1)
  def isBlock: Boolean = false
}

abstract class Reagent[-A, +B] {
  // returns either a BacktrackCommand or a B
  private[chemistry] def tryReact(a: A, rx: Reaction, offer: Offer[B]): Any
  protected def composeI[C](next: Reagent[B,C]): Reagent[A,C]
  private[chemistry] def alwaysCommits: Boolean
  private[chemistry] def maySync: Boolean
  private[chemistry] def snoop(a: A): Boolean

  final def compose[C](next: Reagent[B,C]): Reagent[A,C] = next match {
    case Commit() => this.asInstanceOf[Reagent[A,C]] // B = C
    case _ => composeI(next)
  }

  final def !(a: A): B = tryReact(a, Reaction.inert, null) match {
    case (_: BacktrackCommand) => {
      val backoff = new Backoff
      val maySync = this.maySync // cache
      @tailrec def retryLoop(shouldBlock: Boolean): B = {
	// to think about: can a single waiter be reused?
	val wait = maySync || shouldBlock
	val waiter = if (wait) new Waiter[B](shouldBlock) else null

	tryReact(a, Reaction.inert, waiter) match {
	  case (bc: BacktrackCommand) if wait => {
	    bc.bottom(waiter, backoff, snoop(a))
	    waiter.tryAbort match { // rescind waiter, but check if already
				    // completed
	      case Some(ans) => ans.asInstanceOf[B] 
	      case None      => retryLoop(bc.isBlock)
	    }
	  }
	  case Retry => backoff.once; retryLoop(false)
	  case Block => retryLoop(true)
	  case ans => ans.asInstanceOf[B]
	}
      }
      backoff.once
      retryLoop(false)
    }
    case ans => ans.asInstanceOf[B]
  }

  @inline final def !?(a:A) : Option[B] = {
    tryReact(a, Reaction.inert, null) match {
      case Retry => None // should we actually retry here?  if we do, more
			 // informative: a failed attempt entails a
			 // linearization where no match was possible.  but
			 // could diverge...
      case Block => None
      case ans   => Some(ans.asInstanceOf[B])
    }
  }

  @inline final def dissolve(a:A) = Reagent.dissolve(ret(a) >=> this)

  @inline final def flatMap[C](k: B => Reagent[Unit,C]): Reagent[A,C] = 
    compose(computed(k))
  @inline final def map[C](f: B => C): Reagent[A,C] = 
    compose(lift(f))
 @inline final def >>[C](next: Reagent[Unit,C]): Reagent[A,C] = 
   compose(lift((_:B) => ()).compose(next))
  @inline final def mapFilter[C](f: PartialFunction[B, C]): Reagent[A,C] =
    compose(lift(f))
  @inline final def withFilter(f: B => Boolean): Reagent[A,B] =
    compose(lift((_: B) match { case b if f(b) => b }))
  @inline final def <+>[C <: A, D >: B](that: Reagent[C,D]): Reagent[C,D] = 
    choice(this, that)
  @inline final def >=>[C](k: Reagent[B,C]): Reagent[A,C] =
    compose(k)
}
private object Reagent {
  def dissolve[A](reagent: Reagent[Unit, A]) {
    val cata = new Catalyst(reagent)
    reagent.tryReact((), Reaction.inert, cata) match {
      case Block => return
      case _ => throw Util.Impossible // something has gone awry...
    }
  }
}

private abstract class AutoContImpl[A,B,C](val k: Reagent[B, C]) 
		 extends Reagent[A,C] {
  def retValue(a: A): Any // BacktrackCommand or B
  def newRx(a: A, rx: Reaction): Reaction = rx

  final def snoop(a: A) = retValue(a) match {
    case (_: BacktrackCommand) => false
    case b => k.snoop(b.asInstanceOf[B])
  }
  final def tryReact(a: A, rx: Reaction, offer: Offer[C]): Any = 
    retValue(a) match {
      case (bc: BacktrackCommand) => bc
      case b => k.tryReact(b.asInstanceOf[B], newRx(a, rx), offer)
    }
  final def composeI[D](next: Reagent[C,D]) = 
    new AutoContImpl[A,B,D](k >=> next) {
      def retValue(a: A): Any = 
	AutoContImpl.this.retValue(a)
      override def newRx(a: A, rx: Reaction): Reaction = 
	AutoContImpl.this.newRx(a, rx)
    }
  final def alwaysCommits = k.alwaysCommits // this needs to be overridable!
  final def maySync = k.maySync
}
private abstract class AutoCont[A,B] extends AutoContImpl[A,B,B](Commit[B]())

object ret {
  @inline final def apply[A](pure: A): Reagent[Any,A] = new AutoCont[Any,A] {
    def retValue(a: Any): Any = pure
  }
}

// Not sure whether this should be available as a combinaor
// object retry extends Reagent[Any,Nothing] {
//   final def tryReact[A](a: Any, rx: Reaction, k: K[Nothing,A]): A = 
//     throw ShouldRetry
// }

private final case class Commit[A]() extends Reagent[A,A] {
  def tryReact(a: A, rx: Reaction, offer: Offer[A]): Any = {
    offer match {
      case null => if (rx.tryCommit) a else Retry
//      case (w: Waiter[_]) => if (w.rxWithAbort(rx).tryCommit) a else Retry
      case (w: Waiter[_]) => {
	w.tryAbort match { // rescind waiter, but check if already completed
	  case Some(ans) => ans
	  case None      => if (rx.tryCommit) a else Retry
	}
      }
      case (_: Catalyst[_]) => {
	rx.tryCommit
	Block
      }	
    }
  }
  def snoop(a: A) = true
  def makeOfferI(a: A, offer: Offer[A]) {}
  def composeI[B](next: Reagent[A,B]) = next
  def alwaysCommits = true
  def maySync = false
}

object never extends Reagent[Any, Nothing] {
  def tryReact(a: Any, rx: Reaction, offer: Offer[Nothing]): Any = Block
  def snoop(a: Any) = false
  def composeI[A](next: Reagent[Nothing, A]) = never
  def alwaysCommits = false
  def maySync = false
}
/*
object computed {
  private final case class Computed[A,B,C](c: A => Reagent[Unit,B], 
					   k: Reagent[B,C]) 
		     extends Reagent[A,C] {
    def snoop(a: A) = false
    def tryReact(a: A, rx: Reaction, offer: Offer[C]): Any = 
      c(a).compose(k).tryReact((), rx, offer)
    def composeI[D](next: Reagent[C,D]) = Computed(c, k.compose(next))
    def alwaysCommits = false
    def maySync = true
  }
  @inline def apply[A,B](c: A => Reagent[Unit,B]): Reagent[A,B] = 
    Computed(c, Commit[B]())
}
*/
object computed {
  private final case class Computed[A,B](c: A => Reagent[Unit,B]) 
		     extends Reagent[A,B] {
    def snoop(a: A) = false
    def tryReact(a: A, rx: Reaction, offer: Offer[B]): Any = 
      c(a).tryReact((), rx, offer)
    def composeI[C](next: Reagent[B,C]) = throw Util.Impossible
    def alwaysCommits = false
    def maySync = true
  }
  @inline def apply[A,B](c: A => Reagent[Unit,B]): Reagent[A,B] = 
    Computed(c)
}

object lift {
  // this is WRONG -- does NOT always commit
  @inline def apply[A,B](f: PartialFunction[A,B]): Reagent[A,B] = 
    new AutoCont[A,B] {
      def retValue(a: A): Any = if (f.isDefinedAt(a)) f(a) else Block
    }
}

object choice {
  private final case class Choice[A,B](r1: Reagent[A,B], r2: Reagent[A,B]) 
		     extends Reagent[A,B] {
    def tryReact(a: A, rx: Reaction, offer: Offer[B]): Any = 
      r1.tryReact(a, rx, offer) match {
	case Retry => 
	  r2.tryReact(a, rx, offer) match {
	    case Retry => Retry
	    case Block => Retry // must retry r1
	    case ans   => ans
	  }
	case Block => r2.tryReact(a, rx, offer)
	case ans => ans
      }
    def composeI[C](next: Reagent[B,C]) = 
      next match {
	case Choice(next1, next2) =>
	  Choice(r1 >=> next1,
		 Choice(r1 >=> next2,
			Choice(r2 >=> next1,
			       r2 >=> next2)))
	case _ => Choice(r1.compose(next), r2.compose(next))
      }
    def alwaysCommits = r1.alwaysCommits && r2.alwaysCommits
    def maySync = r1.maySync || r2.maySync
    def snoop(a: A) = r2.snoop(a) || r1.snoop(a) 
  }
  @inline def apply[A,B](r1: Reagent[A,B], r2: Reagent[A,B]): Reagent[A,B] =
    Choice(r1, r2)
}

object postCommit {
  @inline def apply[A](pc: A => Unit): Reagent[A,A] = new AutoCont[A,A] {
    def retValue(a: A): Any = a
    override def newRx(a: A, rx: Reaction): Reaction = 
      rx.withPostCommit((_:Unit) => pc(a))
  }
}
