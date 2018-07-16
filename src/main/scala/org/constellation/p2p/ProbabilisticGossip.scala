package org.constellation.p2p

import java.net.InetSocketAddress

import org.constellation.Data
import org.constellation.primitives.Schema._
import org.constellation.util.ProductHash
import constellation._

import scala.util.Random

trait ProbabilisticGossip extends PeerAuth with LinearGossip {

  val data: Data

  import data._

  def handleGossip(gm : GossipMessage, remote: InetSocketAddress): Unit = {

    totalNumGossipMessages += 1

    val rid = signedPeerLookup.get(remote).map{_.data.id}

    gm match {
      case BatchBundleHashRequest(hashes) =>

        hashes.foreach{h =>
          lookupBundle(h).foreach{
            b =>
              // TODO: Send all meta for sync conflict detection.
              udpActor ! UDPSend(b.bundle, remote)
          }
        }

      case BatchTXHashRequest(hashes) =>

        hashes.foreach{h =>
          lookupTransaction(h).foreach{
            b =>
              udpActor ! UDPSend(b, remote)
          }
        }

      case b: Bundle =>

        handleBundle(b)

      case tx: Transaction =>

        if (lookupTransaction(tx.hash).isEmpty) {
          storeTransaction(tx)
          numSyncedTX += 1
        }

        syncPendingTXHashes -= tx.hash
        txSyncRequestTime.remove(tx.hash)

      case bb: PeerSyncHeartbeat =>

/*        handleBundle(bb.maxBundle)
        rid.foreach{ r =>
          peerSync(r) = bb
        }
        */
        processPeerSyncHeartbeat(bb)


      case g : Gossip[_] =>
      // handleGossipRegular(g, remote)

      case x =>
        logger.debug("Unrecognized gossip message " + x)
    }
  }

  def gossipHeartbeat(): Unit = {
    // Gather any missing transaction or bundle data
    dataRequest()

    // If we have enough data to participate in consensus then tell our peers about our chain state
    if (!downloadMode && genesisBundle.nonEmpty
      && maxBundleMetaData.nonEmpty && !downloadInProgress) {
      bundleHeartbeat()
    }
  }

  def bundleHeartbeat(): Unit = {
    // Bootstrap the initial genesis tx
    acceptInitialDistribution()

    // Lookup from db or ask peers for peer bundle data that is missing
    attemptResolvePeerBundles()

    // TODO: should probably be less disruptive then just restarting
    // check if we are far behind, if so re-sync
    peerSync.foreach{
      _._2.maxBundleMeta.height.foreach{
        h =>
          maxBundleMetaData.flatMap{_.height}.foreach{ h2 =>
            if (h > (h2 + 20)) {
              logger.error("FOUND PEER 20 BUNDLES AHEAD, RESTARTING")
              System.exit(1)
            }
          }
      }
    }

    // TODO: remove, should only live within the test context
    if (generateRandomTX) {
      simulateTransactions()
    }

    // Tell peers about our latest best bundle and chain
    broadcast(PeerSyncHeartbeat(maxBundleMetaData.get, validLedger.toMap, id))

    // Tell peers about our latest mempool state
    poolEmit()

    // Compress down bundles
    combineBundles()

    // clean up any bundles that have been compressed
    cleanupStrayChains()
  }


  def dataRequest(): Unit = {

    // Request missing bundle data
    if (syncPendingBundleHashes.nonEmpty) {
      broadcast(BatchBundleHashRequest(syncPendingBundleHashes))
    }

    // Request missing transaction data
    if (syncPendingTXHashes.nonEmpty) {

      // limit the number of transaction data we request at a time
      if (syncPendingTXHashes.size > 150) {
        val toRemove = txSyncRequestTime.toSeq.sortBy(_._2).zipWithIndex.filter{_._2 > 50}.map{_._1._1}.toSet
        syncPendingTXHashes --= toRemove
      }

      // ask peers for this transaction data
      broadcast(BatchTXHashRequest(syncPendingTXHashes))
    }
  }

  def getParentHashEmitter(stackDepthFilter: Int = minGenesisDistrSize - 1): ParentBundleHash = {
    val mb = maxBundle.get
    def pbHash = ParentBundleHash(mb.hash)
    val pbh = if (totalNumValidatedTX == 1) pbHash
    else if (mb.maxStackDepth < stackDepthFilter) mb.extractParentBundleHash
    else pbHash
    pbh
  }

  def poolEmit(): Unit = {

    val mb = maxBundle
    val pbh = getParentHashEmitter()

    val maybeData = lookupBundle(pbh.pbHash)
    val ids = maybeData.get.bundle.extractIds
    val lastPBWasSelf = maybeData.exists(_.bundle.bundleData.id == id)
    val selfIsFacilitator = (BigInt(pbh.pbHash, 16) % ids.size).toInt == 0
    //val selfIsFacilitator = (BigInt(pbh.pbHash + stackSizeFilter, 16) % ids.size).toInt == 0
    val doEmit = !lastPBWasSelf && selfIsFacilitator

    if (!lastPBWasSelf || totalNumValidatedTX == 1) {

      // Emit an origin bundle. This needs to be managed by prob facil check on hash of previous + ids
      val memPoolEmit = Random.nextInt() < 0.1
      val filteredPool = memPool.diff(txInMaxBundleNotInValidation).filterNot(last10000ValidTXHash.contains)
      val memPoolSelSize = Random.nextInt(5)
      val memPoolSelection = Random.shuffle(filteredPool.toSeq)
        .slice(0, memPoolSelSize + minGenesisDistrSize + 1)

      if (memPoolEmit && filteredPool.nonEmpty) {
        // println(s"Mempool emit on ${id.short}")

        val b = Bundle(
          BundleData(
            memPoolSelection.map {
              TransactionHash
            } :+ pbh
          ).signed()
        )
        val meta = mb.get.meta.get
        updateBundleFrom(meta, Sheaf(b))
        broadcast(b)
      }
    }
  }

  def acceptInitialDistribution(): Unit = {
    // Force accept initial distribution
    if (totalNumValidatedTX == 1 && maxBundle.get.extractTX.size >= (minGenesisDistrSize - 1)) {
      maxBundle.get.extractTX.foreach{tx =>
        acceptTransaction(tx)
        tx.txData.data.updateLedger(memPoolLedger)
      }
      totalNumValidBundles += 1
      last100ValidBundleMetaData :+= maxBundleMetaData.get
    }
  }

  def attemptResolvePeerBundles(): Unit = peerSync.foreach{
    case (id, hb) =>
      if (hb.maxBundle.meta.exists(z => !z.isResolved)) {
        attemptResolveBundle(hb.maxBundle.meta.get, hb.maxBundle.extractParentBundleHash.pbHash)
      }
  }

  def combineBundles(): Unit = {

    // Maybe only emit when PBH matches our current?
    // Need to slice this down here
    // Only emit max by new total score?
    activeDAGBundles.groupBy(b => b.bundle.extractParentBundleHash -> b.bundle.maxStackDepth)
      .filter{_._2.size > 1}.toSeq //.sortBy(z => 1*z._1._2).headOption
      .foreach { case (pbHash, bundles) =>
      if (Random.nextDouble() > 0.6) {
        val best3 = bundles.sortBy(z => -1 * z.totalScore.get).slice(0, 2)
        val allIds = best3.flatMap(_.bundle.extractIds)
        //   if (!allIds.contains(id)) {
        val b = Bundle(BundleData(best3.map {
          _.bundle
        }).signed())
        val maybeData = lookupBundle(pbHash._1.pbHash)
        //     if (maybeData.isEmpty) println(pbHash)
        val pbData = maybeData.get
        updateBundleFrom(pbData, Sheaf(b))
        // Skip ids when depth below a certain amount, else tell everyone.
        // TODO : Fix ^
        broadcast(b, skipIDs = allIds)
      }
/*
          val hasNewTransactions =
            l.bundle.extractTXHash.diff(r.bundle.extractTXHash).nonEmpty
          val minTimeClose = Math.abs(l.rxTime - r.rxTime) < 40000
          if (Random.nextDouble() > 0.5 &&  hasNewTransactions && minTimeClose) { //
          }
      }*/
    }

  }

  def bundleCleanup(): Unit = {
    if (heartbeatRound % 60 == 0 && last100ValidBundleMetaData.size > 50) {
     // println("Bundle cleanup")
      last100ValidBundleMetaData.slice(0, 30).foreach{
        s =>
          s.bundle.extractSubBundleHashes.foreach{
            h =>
              deleteBundle(h)
          }

         // s.bundle.extractTXHash.foreach{ t =>
         //   removeTransactionFromMemory(t.txHash)
         // }

          // Removes even the top level valid bundle after a certain period of time.
      }
    }

    // There should also be
  }

  def cleanupStrayChains(): Unit = {
    activeDAGManager.cleanup(maxBundleMetaData.get.height.get)
    bundleCleanup()
  }

  // TODO: extract to test
  def simulateTransactions(): Unit = {
    if (maxBundleMetaData.exists{_.height.get >= 5} && memPool.size < 50) {
      //if (Random.nextDouble() < .2)
      randomTransaction()
      randomTransaction()
    }
  }

}
