package io.horizontalsystems.bitcoinkit.core

import android.os.Looper
import io.horizontalsystems.bitcoinkit.managers.UnspentOutputProvider
import io.horizontalsystems.bitcoinkit.models.*
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import io.realm.OrderedCollectionChangeSet
import io.realm.OrderedCollectionChangeSet.State
import io.realm.RealmResults
import java.util.concurrent.TimeUnit

class DataProvider(private val realmFactory: RealmFactory, private val listener: Listener, private val unspentOutputProvider: UnspentOutputProvider) {

    interface Listener {
        fun onTransactionsUpdate(inserted: List<TransactionInfo>, updated: List<TransactionInfo>, deleted: List<Int>)
        fun onBalanceUpdate(balance: Long)
        fun onLastBlockInfoUpdate(blockInfo: BlockInfo)
    }

    private val realm = realmFactory.realm
    private val transactionRealmResults = getMyTransactions()
    private val blockRealmResults = getBlocks()
    private val feeRateRealmResults = getFeeRate()
    private val balanceUpdateSubject: PublishSubject<Boolean> = PublishSubject.create()
    private val balanceSubjectDisposable: Disposable

    //  Getters
    var balance: Long = unspentOutputProvider.getBalance()
        private set

    val transactions get() = getMyTransactions().mapNotNull { transactionInfo(it) }

    var lastBlockHeight: Int = blockRealmResults.lastOrNull()?.height ?: 0
        private set

    var feeRate: FeeRate = feeRateRealmResults.firstOrNull() ?: FeeRate.defaultFeeRate
        private set

    init {
        transactionRealmResults.addChangeListener { transactions, changeSet ->
            handleTransactions(transactions, changeSet)
        }

        blockRealmResults.addChangeListener { blocks, changeSet ->
            handleBlocks(blocks, changeSet)
        }

        feeRateRealmResults.addChangeListener { feeRates, _ ->
            feeRate = feeRates.firstOrNull()?.let { realm.copyFromRealm(it) } ?: FeeRate.defaultFeeRate
        }

        balanceSubjectDisposable = balanceUpdateSubject.debounce(500, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.from(Looper.myLooper()))
                .subscribe {
                    balance = unspentOutputProvider.getBalance()
                    listener.onBalanceUpdate(balance)
                }
    }

    fun clear() {
        transactionRealmResults.removeAllChangeListeners()
        blockRealmResults.removeAllChangeListeners()
        realm.close()
        balanceSubjectDisposable.dispose()
    }

    private fun handleTransactions(transactions: RealmResults<Transaction>, changeSet: OrderedCollectionChangeSet) {
        if (changeSet.state == State.UPDATE) {
            listener.let { listener ->
                val inserted = changeSet.insertions.asList().mapNotNull { transactionInfo(transactions[it]) }
                val updated = changeSet.changes.asList().mapNotNull { transactionInfo(transactions[it]) }
                val deleted = changeSet.deletions.asList()

                listener.onTransactionsUpdate(inserted, updated, deleted)
            }
            balanceUpdateSubject.onNext(true)
        }
    }

    private fun handleBlocks(blocks: RealmResults<Block>, changeSet: OrderedCollectionChangeSet) {
        if (changeSet.state == State.UPDATE && (changeSet.deletions.isNotEmpty() || changeSet.insertions.isNotEmpty())) {
            blocks.lastOrNull()?.let { block ->

                lastBlockHeight = block.height

                listener.onLastBlockInfoUpdate(BlockInfo(
                        block.reversedHeaderHashHex,
                        block.height,
                        block.header?.timestamp
                ))
            }
            balanceUpdateSubject.onNext(true)
        }
    }

    private fun transactionInfo(transaction: Transaction?): TransactionInfo? {
        if (transaction == null) return null

        var totalMineInput = 0L
        var totalMineOutput = 0L
        val fromAddresses = mutableListOf<TransactionAddress>()
        val toAddresses = mutableListOf<TransactionAddress>()

        transaction.inputs.forEach { input ->
            input.previousOutput?.let { previousOutput ->
                if (previousOutput.publicKey != null) {
                    totalMineInput += previousOutput.value
                }
            }

            input.address?.let { address ->
                fromAddresses.add(TransactionAddress(address, mine = input.previousOutput?.publicKey != null))
            }
        }

        transaction.outputs.forEach { output ->
            var mine = false

            if (output.publicKey != null) {
                totalMineOutput += output.value
                mine = true
            }

            output.address?.let { address ->
                toAddresses.add(TransactionAddress(address, mine))
            }
        }

        return TransactionInfo(
                transactionHash = transaction.hashHexReversed,
                from = fromAddresses,
                to = toAddresses,
                amount = totalMineOutput - totalMineInput,
                blockHeight = transaction.block?.height,
                timestamp = transaction.block?.header?.timestamp
        )
    }

    private fun getBlocks(): RealmResults<Block> {
        return realm.where(Block::class.java)
                .sort("height")
                .findAll()
    }

    private fun getFeeRate(): RealmResults<FeeRate> {
        return realm.where(FeeRate::class.java).findAll()
    }

    private fun getMyTransactions(): RealmResults<Transaction> {
        return realmFactory.realm.where(Transaction::class.java)
                .equalTo("isMine", true)
                .findAll()
    }
}
