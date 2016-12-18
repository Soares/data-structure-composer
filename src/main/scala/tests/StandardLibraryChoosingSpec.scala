package tests

/**
  * Created by buck on 9/12/16.
  */

import cli.DataStructureChooserCli
import implementationSearcher._
import org.scalatest.FunSpec
import parsers.MainParser
import shared._

class StandardLibraryChoosingSpec extends FunSpec {
  val minStackAdt = MainParser.nakedAdt.parse("""
    adt MinStack {
      insertLast! -> 1
      deleteLast! -> 1
      getByIndex -> 1
      getMinimum -> 1
    }""".trim()).get.value

  val impls: Set[FreeImpl] = DataStructureChooserCli.impls
  val decls = DataStructureChooserCli.decls
  val structures = DataStructureChooserCli.structures
  val library = ImplLibrary(impls, decls, structures)

  describe("regressions") {
    it("knows about how reduce works") {
      val res = Searcher.getAllTimesForDataStructures(library, Set(library.structures("ArrayList")))
      assert(res.readMethods.getNamed("reduce").exists(_.rhs == Impl.rhs("n * f + n")))
    }

    it("knows some things about getFirstBy") {
      val impl = FreeImpl.parse("getLastBy[f] <- reduce[_{commutative} <- f]")
      val unfreeImplSet = Searcher.getAllTimesForDataStructures(library, Set(library.structures("InvertibleReductionMemoizer"))).readMethods

      assert(unfreeImplSet.impls(MethodName("reduce")).implsWhichMatchMethodExpr(
        MethodExpr.parse("reduce[_{commutative} <- f]"),
        unfreeImplSet,
        ParameterList.apply(ImplPredicateMap.empty, List(MethodName("f"))),
        decls) == Set())

      assert(unfreeImplSet.getNamedWithoutSource("getSum") == Set(Impl("getSum <- 1")))
      assert(unfreeImplSet.getNamedWithoutSource("getLastBy") == Set())
    }
  }

  describe("simple choosing with stdlib impls") {
    describe("with min stack") {
      it("can correctly evaluate the performance of a generic heap and vector list") {
        val res = Searcher.getAllTimesForDataStructures(
          library,
          Set(structures("BinaryHeap"), structures("ArrayList"))
        ).fullUnfreeImplSet

        assert(res.getNamedWithoutSource("getMinimum") == Set(Impl("getMinimum <- 1")))
        assert(res.getNamedWithoutSource("insertLast!") == Set(Impl("insertLast! <- log(n)")))
      }

      it("can correctly evaluate the performance of a stack min memoizer and vector list") {
        val res = Searcher.getAllTimesForDataStructures(
          library,
          Set(structures("DequeReductionMemoizer"), structures("ArrayList"))
        ).fullUnfreeImplSet

        assert(res.getNamedWithoutSource("getMinimum") == Set(Impl("getMinimum <- 1")))
        assert(res.getNamedWithoutSource("insertLast!") == Set(Impl("insertLast! <- 1")))
      }
    }
  }

  describe("data structure analysis") {
    it("can do a stack") {
      val adt = MainParser.nakedAdt.parse("""
        adt Stack {
          insertLast! -> 1
          deleteLast! -> 1
          getByIndex -> 1
          updateNode! -> 1
        }""".trim()).get.value

      val res = DataStructureChooserCli.chooseDataStructures(adt)

      assert(res.items.head.structures.map(_.name) == Set("ArrayList"))
      res.items.foreach(_.frontendResult.get)
    }

    it("can do a list") {
      val adt = MainParser.nakedAdt.parse("""
        adt List {
          insertAtIndex! -> 1
          getByIndex -> 1
          updateNode! -> 1
        }""".trim()).get.value

      val res = DataStructureChooserCli.chooseDataStructures(adt)

      assert(res.items.map(_.structureNames).contains(Set("OrderStatisticTreeList")))
      res.items.foreach(_.frontendResult.get)
    }

    it("can do a min-stack") {
      val res = DataStructureChooserCli.chooseDataStructures(minStackAdt)

      assert(res.items.map(_.structures.map(_.name)) == Set(Set("StackReductionMemoizer", "ArrayList")))

      DataStructureChooserCli.chooseAllDataStructures(minStackAdt).items.foreach(_.frontendResult.get)
    }

    it("can do a stack with contains") {
      val res = DataStructureChooserCli.chooseDataStructures(MainParser.nakedAdt.parse("""
        adt Stack {
          insertLast! -> 1
          deleteLast! -> 1
          getByIndex -> 1
          updateNode! -> 1
          contains -> 1
        }""".trim()).get.value)

      assert(res.items.exists(_.structureNames == Set("HistogramHashMap", "ArrayList")))

      val List(firstChoice) = res.items.toList
      val containsImpl: BoundImpl = firstChoice.readMethods.getNamed(MethodName("contains")).head

      assert(firstChoice.readMethods.getMatchingImpl(containsImpl.impl).isDefined)
      res.items.foreach(_.frontendResult.get)
    }

    it("can do a set which you never delete from") {
      val res = DataStructureChooserCli.chooseDataStructures(AbstractDataType.parse("""
        adt NeverDeletedSet {
          insertLast! -> 1
          contains -> 1
        }""".trim()))

      assert(res.items.map(_.structureNames) == Set(Set("HistogramHashMap")))
      res.items.foreach(_.frontendResult.get)
    }

    it("knows how to use parameterized data structures") {
      val adt = MainParser.nakedAdt.parse("""
        adt RmqList {
          insertAtIndex! -> 1
          getByIndex -> 1
          rangeMinimumQuery -> 1
        }""".trim()).get.value

      val res = DataStructureChooserCli.chooseDataStructures(adt)

      assert(res.items.head.structureNames == Set("AugmentedOrderStatisticTreeList"))
      res.items.foreach(_.frontendResult.get)
    }

    it("knows how to use RMQ") {
      val adt = MainParser.nakedAdt.parse("""
        adt RmqList {
          insertLast! -> 1
          getByIndex -> 1
          rangeMinimumQuery -> n
        }""".trim()).get.value

      val res = DataStructureChooserCli.chooseDataStructures(adt)

      assert(res.items.head.structureNames == Set("SparseTableForIdempotentReduction", "ArrayList"))
      res.items.foreach(_.frontendResult.get)
    }

    it("can solve RMQ and count") {
      val adt = MainParser.nakedAdt.parse("""
        adt RmqList {
          insertLast! -> 1
          getByIndex -> 1
          rangeMinimumQuery -> 1
          count -> 1
        }""".trim()).get.value

      val res = Searcher.allMinTotalCostParetoOptimalDataStructureCombosForAdt(
        ImplLibrary(impls, decls, DataStructureChooserCli.structures.filterKeys(
          List("SparseTableForIdempotentReduction", "HistogramHashMap", "ArrayList").contains(_))),
        adt)

      assert(res.items.exists(_.structureNames ==
        Set("SparseTableForIdempotentReduction", "HistogramHashMap", "ArrayList")))

      res.items.foreach(_.frontendResult.get)
    }

    it("can do sum stack with random modification") {
      val adt = MainParser.nakedAdt.parse("""
        adt RandomlyAccessibleSumStack {
          insertLast! -> 1
          deleteLast! -> 1
          getSum -> 1
          getByIndex -> 1
          updateNode! -> 1
        }""".trim()).get.value

      val res = DataStructureChooserCli.chooseDataStructures(adt)
      assert(res.items.head.structureNames == Set("ArrayList", "InvertibleReductionMemoizer"))
      res.items.foreach(_.frontendResult.get)

      DataStructureChooserCli.chooseAllDataStructures(adt).items.foreach(_.frontendResult.get)
    }

    it("can do some things that used to break") {
      val adt = MainParser.nakedAdt.parse("""
        adt Thing {
          deleteLast! -> 1
          deleteMinimum! -> 1
          getMinimum -> 1
          insertLast! -> 1
        }""".trim()).get.value

      val res = DataStructureChooserCli.chooseDataStructures(adt)

      DataStructureChooserCli.chooseAllDataStructures(adt).items.foreach(_.frontendResult.get)

      res.items.map(_.frontendResult.get)
    }

    it("can do min-stack with random modification") {
      val adt = MainParser.nakedAdt.parse("""
        adt MinStackWithRandomModification {
          insertLast! -> 1
          deleteLast! -> 1
          getMinimum -> 1
          getByIndex -> 1
          updateNode! -> 1
        }""".trim()
      ).get.value

      val res2 = Searcher.getAllTimesForDataStructures(library,
              Set(structures("InvertibleReductionMemoizer"), structures("ArrayList")))

      assert(res2.readMethods.getNamedWithoutSource("getMinimum") == Set(Impl("getMinimum <- n")))
      val res = DataStructureChooserCli.chooseDataStructures(adt)

      assert(res.items.map(_.structureNames) == Set(
        Set("ArrayList", "ValueOrderedOrderStatisticTree"),
        Set("ArrayList", "BinaryHeap")
      ))

      DataStructureChooserCli.chooseAllDataStructures(adt).items.foreach(_.frontendResult.get)
    }

    it("can do min stack with getKthBy") {
      val adt = MainParser.nakedAdt.parse("""
        adt MinStack {
          insertLast! -> 1
          deleteLast! -> 1
          getByIndex -> 1
          getMinimum -> 1
          getKthBy[_] -> 1
        }""".trim()
      ).get.value

      val res2 = Searcher.getAllTimesForDataStructures(library,
        Set(structures("InvertibleReductionMemoizer"), structures("ArrayList")))

      assert(res2.readMethods.getNamedWithoutSource("getMinimum") == Set(Impl("getMinimum <- n")))
      val res = DataStructureChooserCli.chooseDataStructures(adt)

      assert(res.items.map(_.structureNames) == Set(Set("ArrayList", "ValueOrderedOrderStatisticTree")))

      res.items.foreach(_.frontendResult.get)

      DataStructureChooserCli.chooseAllDataStructures(adt).items.foreach(_.frontendResult.get)
    }
  }

  describe("data structure dominance") {
    describe("partialCompareFromExtensionRelation") {
      it("knows how to deal when they're the same") {
        assert(library.partialCompareFromExtensionRelation(
          library.structures("ArrayList"), library.structures("ArrayList")) == NeitherDominates)
      }

      it("knows how to deal when they're unrelated") {
        assert(library.partialCompareFromExtensionRelation(
          library.structures("ArrayList"), library.structures("BinaryHeap")) == NeitherDominates)
      }

      it("deals when they're related") {
        assert(
          library.partialCompareFromExtensionRelation(
            library.structures("ValueOrderedOrderStatisticTree"), library.structures("ValueOrderedAugmentedOrderStatisticTree"))
            == LeftStrictlyDominates)
      }
    }

    describe("partialCompareSetFromExtensionRelations") {
      it("deals with unrelated sets properly") {
        assert(library.partialCompareSetFromExtensionRelations(
          Set(library.structures("ArrayList")), Set(library.structures("BinaryHeap"))) == NeitherDominates)
      }

      it("deals with related sets properly") {
        assert(library.partialCompareSetFromExtensionRelations(
          Set(library.structures("ValueOrderedOrderStatisticTree")), Set(library.structures("ValueOrderedAugmentedOrderStatisticTree"))) == LeftStrictlyDominates)
      }
    }
  }
}
