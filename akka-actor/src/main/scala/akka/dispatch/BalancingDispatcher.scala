/**
 *    Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import util.DynamicVariable
import akka.actor.{ ActorCell, Actor, IllegalActorStateException }

/**
 * An executor based event driven dispatcher which will try to redistribute work from busy actors to idle actors. It is assumed
 * that all actors using the same instance of this dispatcher can process all messages that have been sent to one of the actors. I.e. the
 * actors belong to a pool of actors, and to the client there is no guarantee about which actor instance actually processes a given message.
 * <p/>
 * Although the technique used in this implementation is commonly known as "work stealing", the actual implementation is probably
 * best described as "work donating" because the actor of which work is being stolen takes the initiative.
 * <p/>
 * The preferred way of creating dispatchers is to use
 * the {@link akka.dispatch.Dispatchers} factory object.
 *
 * @see akka.dispatch.BalancingDispatcher
 * @see akka.dispatch.Dispatchers
 *
 * @author Viktor Klang
 */
class BalancingDispatcher(
  _name: String,
  throughput: Int = Dispatchers.THROUGHPUT,
  throughputDeadlineTime: Int = Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
  mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  config: ThreadPoolConfig = ThreadPoolConfig())
  extends Dispatcher(_name, throughput, throughputDeadlineTime, mailboxType, config) {

  def this(_name: String, throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) =
    this(_name, throughput, throughputDeadlineTime, mailboxType, ThreadPoolConfig()) // Needed for Java API usage

  def this(_name: String, throughput: Int, mailboxType: MailboxType) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(_name: String, throughput: Int) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(_name: String, _config: ThreadPoolConfig) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE, _config)

  def this(_name: String, memberType: Class[_ <: Actor]) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(_name: String, mailboxType: MailboxType) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  @volatile
  private var members = Vector[ActorCell]()
  private val donationInProgress = new DynamicVariable(false)

  protected[akka] override def register(actor: ActorCell) = {
    members :+= actor //Update members, doesn't need synchronized, is guarded in attach
    super.register(actor)
  }

  protected[akka] override def unregister(actor: ActorCell) = {
    members = members.filterNot(actor eq) //Update members, doesn't need synchronized, is guarded in detach
    super.unregister(actor)
  }

  override protected[akka] def dispatch(invocation: Envelope) = {
    val mbox = invocation.receiver.mailbox
    if (donationInProgress.value == false && (mbox.hasMessages || mbox.dispatcherLock.locked) && attemptDonationOf(invocation, mbox)) {
      //We were busy and we got to donate the message to some other lucky guy, we're done here
    } else {
      mbox enqueue invocation
      registerForExecution(mbox)
    }
  }

  override protected[akka] def reRegisterForExecution(mbox: Mailbox): Unit = {
    try {
      donationInProgress.value = true
      while (donateFrom(mbox)) {} //When we reregister, first donate messages to another actor
    } finally { donationInProgress.value = false }

    if (mbox.hasMessages) //If we still have messages left to process, reschedule for execution
      super.reRegisterForExecution(mbox)
  }

  /**
   * Returns true if it successfully donated a message
   */
  protected def donateFrom(donorMbox: Mailbox): Boolean = {
    val actors = members // copy to prevent concurrent modifications having any impact

    // we risk to pick a thief which is unregistered from the dispatcher in the meantime, but that typically means
    // the dispatcher is being shut down...
    // Starts at is seeded by current time
    doFindDonorRecipient(donorMbox, actors, (System.currentTimeMillis % actors.size).asInstanceOf[Int]) match {
      case null      ⇒ false
      case recipient ⇒ donate(donorMbox.dequeue, recipient)
    }
  }

  /**
   * Returns true if the donation succeeded or false otherwise
   */
  protected def attemptDonationOf(message: Envelope, donorMbox: Mailbox): Boolean = try {
    donationInProgress.value = true
    val actors = members // copy to prevent concurrent modifications having any impact
    doFindDonorRecipient(donorMbox, actors, System.identityHashCode(message) % actors.size) match {
      case null      ⇒ false
      case recipient ⇒ donate(message, recipient)
    }
  } finally { donationInProgress.value = false }

  /**
   * Rewrites the message and adds that message to the recipients mailbox
   * returns true if the message is non-null
   */
  protected def donate(organ: Envelope, recipient: ActorCell): Boolean = {
    if (organ ne null) {
      recipient.postMessageToMailbox(organ.message, organ.channel)
      true
    } else false
  }

  /**
   * Returns an available recipient for the message, if any
   */
  protected def doFindDonorRecipient(donorMbox: Mailbox, potentialRecipients: Vector[ActorCell], startIndex: Int): ActorCell = {
    val prSz = potentialRecipients.size
    var i = 0
    var recipient: ActorCell = null

    while ((i < prSz) && (recipient eq null)) {
      val actor = potentialRecipients((i + startIndex) % prSz) //Wrap-around, one full lap
      actor.mailbox match {
        case `donorMbox` | `deadLetterMailbox` ⇒ //Not interesting
        case mbox ⇒
          if (!mbox.hasMessages) //Don't donate to yourself
            recipient = actor //Found!
      }

      i += 1
    }

    recipient // nothing found, reuse same start index next time
  }
}
