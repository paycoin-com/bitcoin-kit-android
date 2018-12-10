package io.horizontalsystems.bitcoinkit.managers

import io.horizontalsystems.bitcoinkit.core.ISyncStateListener
import io.horizontalsystems.bitcoinkit.core.RealmFactory
import io.horizontalsystems.bitcoinkit.models.BlockHash
import io.horizontalsystems.bitcoinkit.models.PublicKey
import io.horizontalsystems.bitcoinkit.network.peer.PeerGroup
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.util.logging.Logger

class InitialSyncer(
        private val realmFactory: RealmFactory,
        private val syncerApi: InitialSyncerApi,
        private val stateManager: StateManager,
        private val addressManager: AddressManager,
        private val peerGroup: PeerGroup,
        private val listener: ISyncStateListener) {

    private val logger = Logger.getLogger("InitialSyncer")
    private val disposables = CompositeDisposable()
    private var isSyncing = false

    @Throws
    fun sync() {
        addressManager.fillGap()

        if (stateManager.restored) return peerGroup.start()
        if (isSyncing) return else {
            isSyncing = true
        }

        listener.onSyncStart()

        val externalObservable = syncerApi.fetchFromApi(true)
        val internalObservable = syncerApi.fetchFromApi(false)

        val disposable = Single
                .merge(externalObservable, internalObservable)
                .toList()
                .subscribeOn(Schedulers.io())
                .subscribe({ pairsList ->
                    val publicKeys = mutableListOf<PublicKey>()
                    val blockHashes = mutableListOf<BlockHash>()
                    pairsList.forEach { (keys, hashes) ->
                        publicKeys.addAll(keys)
                        blockHashes.addAll(hashes)
                    }
                    isSyncing = false
                    handle(publicKeys, blockHashes)
                }, {
                    isSyncing = false
                    logger.severe("Initial Sync Error: $it")
                    listener.onSyncStop()
                })

        disposables.add(disposable)
    }

    fun stop() {
        disposables.clear()
    }

    @Throws
    private fun handle(keys: List<PublicKey>, blockHashes: List<BlockHash>) {

        realmFactory.realm.use { realm ->
            realm.executeTransaction {
                it.insertOrUpdate(blockHashes)
            }
        }

        addressManager.addKeys(keys)

        stateManager.restored = true
        peerGroup.start()
    }

}
