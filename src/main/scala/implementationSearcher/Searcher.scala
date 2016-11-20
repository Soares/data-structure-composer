package implementationSearcher


import parsers.MainParser
import shared._

import scala.collection.mutable
import scala.io.Source

/**
  * Created by buck on 7/25/16.
  */
object Searcher {
  // x[f] if x.foo <- 1
  // y[g] <- x[g]
  // should infer
  // y[g] if g.foo <- 1

  def getAllTimesFromEmpty(impls: Set[FreeImpl], library: ImplLibrary, freeVariables: Set[MethodName]): UnfreeImplSet = {
    getAllTimes(UnfreeImplSet(Map(), freeVariables), impls, library)
  }

  def getAllTimes(initialUnfreeImplSet: UnfreeImplSet,
                  impls: Set[FreeImpl],
                  implLibrary: ImplLibrary): UnfreeImplSet = {
    val queue = mutable.Set[BoundImpl]()

    var unfreeImplSet = initialUnfreeImplSet

    queue ++= impls
      .filter(_.unboundCostTuples(unfreeImplSet, implLibrary.decls).isEmpty)
      .map(_.makeBound(unfreeImplSet, implLibrary.decls))

    def queuePlusSelected: List[BoundImpl] = queue.toList ++ unfreeImplSet.allImpls

    while (queue.nonEmpty) {
      val unfreeImpl: BoundImpl = queue.minBy(_.rhs.minCost)

      queue.remove(unfreeImpl)

      if (unfreeImplSet.isOtherImplUseful(unfreeImpl)) {
        unfreeImplSet = unfreeImplSet.addImpl(unfreeImpl)

        // todo: put a Map in here instead of looping over everything, to speed this up?
        for (otherImpl <- impls) {
          // So we have a random impl. Let's see if the unfreeImpl we just settled on is useful for that impl.
          // It's only useful if unfreeImpl's methodName is used by the rhs of the otherImpl
          // (this condition is weaker than it could be)

          val otherImplMethodsUsed = otherImpl.getNames

          if (otherImplMethodsUsed.contains(unfreeImpl.name)) {
            val neighborUnfreeImpls: DominanceFrontier[BoundUnnamedImpl] = otherImpl.bindToAllOptions(unfreeImplSet, implLibrary.decls)

            neighborUnfreeImpls.items.foreach((u: BoundUnnamedImpl) =>
              if (unfreeImplSet.isOtherImplUseful(u.withName(otherImpl.name))) {
                val impl = u.withName(otherImpl.name)
//                assert(impl.unboundCostTuples(unfreeImplSet, implLibrary.decls).isEmpty)
                queue += impl
              }
            )
          }
        }
      }
    }

    unfreeImplSet
  }

  def getAllTimesForDataStructure(implLibrary: ImplLibrary, dataStructure: DataStructure): UnfreeImplSet = {
    // todo: consider conditions
    getAllTimes(
      UnfreeImplSet(Map(), dataStructure.parameters.toSet),
      implLibrary.impls.union(dataStructure.freeImpls),
      implLibrary)
  }


  def getAllTimesForDataStructures(implLibrary: ImplLibrary, structures: Set[DataStructure]): DataStructureChoice = {
    getRelevantTimesForDataStructures(implLibrary, structures, AbstractDataType.blank)
  }

  def getRelevantTimesForDataStructures(implLibrary: ImplLibrary,
                                        structures: Set[DataStructure],
                                        adt: AbstractDataType,
                                        mbRelevantReadMethods: Option[Set[FreeImpl]] = None,
                                        mbUnfreeImpls: Option[Set[BoundImpl]] = None): DataStructureChoice = {
    val allProvidedReadImplementations: Set[FreeImpl] = structures.flatMap(_.readMethods)

    val allFreeVariables = structures.flatMap(_.parameters)

    val relevantImpls = mbRelevantReadMethods.getOrElse(implLibrary.readMethods)

    val bestReadImplementations: UnfreeImplSet = getAllTimes(
      UnfreeImplSet(Map(), allFreeVariables),
      allProvidedReadImplementations ++ implLibrary.readMethods,
      implLibrary)

    val allWriteImplementations: Map[DataStructure, UnfreeImplSet] = structures.map((s) =>
      s -> getAllTimes(bestReadImplementations, s.writeMethods ++ implLibrary.writeMethods, implLibrary)).toMap

    DataStructureChoice.build(allWriteImplementations, bestReadImplementations, adt, implLibrary)
  }

  def dataStructureComboSearch(library: ImplLibrary,
                               adt: AbstractDataType,
                               alreadyChosen: Set[DataStructure],
                               relevantReadMethods: Set[FreeImpl],
                               structuresToConsider: Set[DataStructure],
                               previousReadResult: UnfreeImplSet
                              ): Set[DataStructureChoice] = {
    if (structuresToConsider.isEmpty) {
      Set()
    } else {
      val (head, tail) = structuresToConsider.head -> structuresToConsider.tail

      if (head.impls.exists((impl) => {
        !impl.name.isMutating && (impl.unboundCostTuples(previousReadResult, library.decls).nonEmpty ||
          previousReadResult.isOtherImplUseful(impl))
      })) {
        val result: DataStructureChoice =
          getRelevantTimesForDataStructures(library, alreadyChosen + head, adt, Some(relevantReadMethods))

        val filteredTail = tail.filter({ (ds: DataStructure) =>
          ! library.oneDsExtendsOther(ds, head)})

        dataStructureComboSearch(library, adt, alreadyChosen + head, relevantReadMethods, filteredTail, result.readMethods) ++
          dataStructureComboSearch(library, adt, alreadyChosen, relevantReadMethods, tail, previousReadResult) ++
          (if (result.overallTimeForAdt.isDefined) Set(result) else Set())
      } else {
        dataStructureComboSearch(library, adt, alreadyChosen, relevantReadMethods, tail, previousReadResult)
      }
    }
  }

  def allParetoOptimalDataStructureCombosForAdt(library: ImplLibrary,
                                                adt: AbstractDataType): DominanceFrontier[DataStructureChoice] = {
    println(s"Potentially relevant data structures: ${library.potentiallyRelevantDataStructures(adt).map(_.name)}")

    val results = dataStructureComboSearch(
      library,
      adt,
      Set(),
      library.readMethods.filter((x) => library.isImplRelevantToAdt(x.impl, adt)),
      library.potentiallyRelevantDataStructures(adt),
      UnfreeImplSet(Map(), Set())
    )

    println(s"There are ${results.size} different composite data structures we considered")

    // A dominance frontier on choices, ranked by:
    // - simplicity, measured by which data structures are used. Eg Set(a, b, c) is worse than Set(a, b).
    //   - This also takes into account the explicit measure of simplicity that the data structures
    //     themselves maintain. So if Deque extends Stack, we prefer Stack, ceteris paribus.
    // - time on the methods which the ADT cares about
    DominanceFrontier.fromSetWithOrder(results, library.dataStructureChoicePartialOrdering)
  }

  // this is the ultimate method
  def allMinTotalCostParetoOptimalDataStructureCombosForAdt(library: ImplLibrary,
                                                            adt: AbstractDataType): DominanceFrontier[DataStructureChoice] = {
    adt.methods.keys.foreach((x) => {
      assert(library.decls.contains(x.name),
        s"Your ADT referred to ${x.name}, which doesn't exist")
    })

    implicit val ordering = new DataStructureChoice.DataStructureChoicePartialOrdering(library)

    val frontier = allParetoOptimalDataStructureCombosForAdt(library, adt)

    if (frontier.items.nonEmpty) {
      val bestTime = frontier.items.map(_.overallTimeForAdt.get).min

      frontier.filter(_.overallTimeForAdt.get == bestTime)
    } else {
      DominanceFrontier.empty[DataStructureChoice]
    }
  }


}

