package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.internal.FetchDataFlow
import net.corda.core.internal.ResolveTransactionsFlow
import net.corda.core.internal.checkParameterHash
import net.corda.core.internal.pushToLoggingContext
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.trace
import net.corda.core.utilities.unwrap
import java.security.SignatureException

/**
 * The [ReceiveTransactionFlow] should be called in response to the [SendTransactionFlow].
 *
 * This flow is a combination of [FlowSession.receive], resolve and [SignedTransaction.verify]. This flow will receive the
 * [SignedTransaction] and perform the resolution back-and-forth required to check the dependencies and download any missing
 * attachments. The flow will return the [SignedTransaction] after it is resolved and then verified using [SignedTransaction.verify].
 *
 * Please note that it will *not* store the transaction to the vault unless that is explicitly requested and checkSufficientSignatures is true.
 * Setting statesToRecord to anything else when checkSufficientSignatures is false will *not* update the vault.
 *
 * @property otherSideSession session to the other side which is calling [SendTransactionFlow].
 * @property checkSufficientSignatures if true checks all required signatures are present. See [SignedTransaction.verify].
 * @property statesToRecord which transaction states should be recorded in the vault, if any.
 */
open class ReceiveTransactionFlow @JvmOverloads constructor(private val otherSideSession: FlowSession,
                                                            private val checkSufficientSignatures: Boolean = true,
                                                            private val statesToRecord: StatesToRecord = StatesToRecord.NONE) : FlowLogic<SignedTransaction>() {
    @Suppress("KDocMissingDocumentation")
    @Suspendable
    @Throws(SignatureException::class,
            AttachmentResolutionException::class,
            TransactionResolutionException::class,
            TransactionVerificationException::class)
    override fun call(): SignedTransaction {
        if (checkSufficientSignatures) {
            logger.trace { "Receiving a transaction from ${otherSideSession.counterparty}" }
        } else {
            logger.trace { "Receiving a transaction (but without checking the signatures) from ${otherSideSession.counterparty}" }
        }
        var endSession = false
        val stx = otherSideSession.receive<SignedTransaction>().unwrap {
            it.pushToLoggingContext()
            logger.info("Received transaction acknowledgement request from party ${otherSideSession.counterparty}.")
            checkParameterHash(it.networkParametersHash)

            // Lines below this comment remove walking the chain from the peers in case the notary exists and is validating
            val isValidatingNotary = serviceHub.networkMapCache.isValidatingNotary(it.notary!!)
            if (!isValidatingNotary) {
                subFlow(ResolveTransactionsFlow(it, otherSideSession, statesToRecord))
                logger.info("Transaction dependencies resolution completed.")
            } else {
                endSession = true
                logger.info("Peers are not performing the transaction dependencies resolution.")
            }
            try {
                it.verify(serviceHub, checkSufficientSignatures)
                // No need to record as we only do it after it has reached the finalityFlow (StatesToRecord.ONLY_RELEVANT)
                //serviceHub.recordTransactions(statesToRecord, listOf(it))
                it
            } catch (e: Exception) {
                logger.warn("Transaction verification failed.")
                throw e
            }
        }
        // Do not let the other side infinitely wait for the Request.End in case there is no walking the chain
        if (endSession) {
            otherSideSession.send(FetchDataFlow.Request.End)
        }
        if (checkSufficientSignatures) {
            // We end up here after the finality flow (i.e receiveFinalityFlow)
            // We should only send a transaction to the vault for processing if we did in fact fully verify it, and
            // there are no missing signatures. We don't want partly signed stuff in the vault.
            checkBeforeRecording(stx)
            logger.info("Successfully received fully signed tx. Sending it to the vault for processing.")
            serviceHub.recordTransactions(statesToRecord, setOf(stx))
            logger.info("Successfully recorded received transaction locally.")
        }
        return stx
    }

    /**
     * Hook to perform extra checks on the received transaction just before it's recorded. The transaction has already
     * been resolved and verified at this point.
     */
    @Suspendable
    @Throws(FlowException::class)
    protected open fun checkBeforeRecording(stx: SignedTransaction) = Unit
}

/**
 * The [ReceiveStateAndRefFlow] should be called in response to the [SendStateAndRefFlow].
 *
 * This flow is a combination of [FlowSession.receive] and resolve. This flow will receive a list of [StateAndRef]
 * and perform the resolution back-and-forth required to check the dependencies.
 * The flow will return the list of [StateAndRef] after it is resolved.
 */
// @JvmSuppressWildcards is used to suppress wildcards in return type when calling `subFlow(new ReceiveStateAndRef<T>(otherParty))` in java.
class ReceiveStateAndRefFlow<out T : ContractState>(private val otherSideSession: FlowSession) : FlowLogic<@JvmSuppressWildcards List<StateAndRef<T>>>() {
    @Suspendable
    override fun call(): List<StateAndRef<T>> {
        return otherSideSession.receive<List<StateAndRef<T>>>().unwrap {
            val txHashes = it.asSequence().map { it.ref.txhash }.toSet()
            subFlow(ResolveTransactionsFlow(txHashes, otherSideSession))
            it
        }
    }
}
