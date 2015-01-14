/*
 * o                        o     o   o         o
 * |             o          |     |\ /|         | /
 * |    o-o o--o    o-o  oo |     | O |  oo o-o OO   o-o o   o
 * |    | | |  | | |    | | |     |   | | | |   | \  | |  \ /
 * O---oo-o o--O |  o-o o-o-o     o   o o-o-o   o  o o-o   o
 *             |
 *          o--o
 * o--o              o               o--o       o    o
 * |   |             |               |    o     |    |
 * O-Oo   oo o-o   o-O o-o o-O-o     O-o    o-o |  o-O o-o
 * |  \  | | |  | |  | | | | | |     |    | |-' | |  |  \
 * o   o o-o-o  o  o-o o-o o o o     o    | o-o o  o-o o-o
 *
 * Logical Markov Random Fields.
 *
 * Copyright (C) 2012  Anastasios Skarlatidis.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package lomrf.mln.grounding

import java.util

import gnu.trove.set.hash.TIntHashSet
import lomrf.logic._
import lomrf.logic.{Literal, AtomSignature, KBParser}
import lomrf.mln.model.{AtomIdentifier, MLN}
import lomrf.util.Cartesian.CartesianIterator
import lomrf.util.{Utilities, AtomEvidenceDB}
import org.scalatest.{FunSpec, Matchers}
import lomrf.tests.ECExampleDomain1._

/**
 * @author Anastasios Skarlatidis
 */
class GroundingSpecTest extends FunSpec with Matchers {


  private val parser = new KBParser(predicateSchema, functionsSchema)

  private val atomIdentifier = AtomIdentifier(predicateSchema, constants, queryAtoms, hiddenAtoms)

  // Manually create sample evidence
  private val atomStateDB: Map[AtomSignature, AtomEvidenceDB] = {

    var result = Map[AtomSignature, AtomEvidenceDB]()

    // All predicates with open-world assumption have unknown state values --- i.e., HoldsAt/2, InitiatedAt/2 and TerminatedAt/2
    for (signature <- owa)
      result += signature -> AtomEvidenceDB.OWA(atomIdentifier.identities(signature))

    // Add all positive instantiations of predicate Next/2 (for time points 1 to 100)
    val nextSignature = AtomSignature("Next", 2)
    val nextIDF = atomIdentifier.identities(nextSignature)
    val nextPositives = new TIntHashSet()
    (1 until LAST_TIME_POINT).map(t => nextIDF.encode(Seq((t + 1).toString, t.toString))).foreach(nextPositives.add)
    result += (nextSignature -> AtomEvidenceDB.CWA(nextPositives, nextIDF))

    // Assume true All instantiations of predicate Happens/2 having its first argument equals with 'walking' and
    // the rest instantiations are false.
    val happensSignature = AtomSignature("Happens", 2)
    val happensIDF = atomIdentifier.identities(happensSignature)
    val happensPositives = new TIntHashSet()
    (1 to LAST_TIME_POINT).map(t => happensIDF.encode(Seq("Walking", t.toString))).foreach(happensPositives.add)
    result += (happensSignature -> AtomEvidenceDB.CWA(happensPositives, happensIDF))

    result
  }


  //val formulaStr = "HoldsAt(f,t) ^ !TerminatedAt(f,t) ^ Next(t,tNext) => HoldsAt(f,tNext)."
  val formulaStr = "HoldsAt(f,t) ^ !TerminatedAt(f,t) ^ !TerminatedAt(f,t1) ^ Next(t,tNext) => HoldsAt(f,tNext)."

  describe("Formula '" + formulaStr + "'") {
    val formula = parser.parseFormula(formulaStr)
    val clauses = formula.toCNF(constants)

    it("produces a single clause") {
      clauses.size should be(1)
    }

    val clause = clauses.head

    it("contains three variables") {
      clause.variables.size should be(4)
    }

    val mln = new MLN(
      formulas = Set(formula),
      predicateSchema,
      functionsSchema,
      dynamicAtoms,
      dynamicFunctions,
      constants,
      functionMappers,
      queryAtoms,
      cwa,
      owa,
      probabilisticAtoms = Set.empty[AtomSignature],
      tristateAtoms = Set.empty[AtomSignature],
      atomStateDB,
      atomIdentifier
    )


    val orderedLiterals: Array[Literal] =
      clause
        .literals
        .toArray
        .sortBy(l => l)(ClauseLiteralsOrdering(mln))

    info("original sequence of literals : " + clause.literals.map(_.toString).mkString(" v ") + "\n" +
      "ordered sequence of literals  : " + orderedLiterals.map(_.toString).mkString(" v "))




    //
    // Utility arrays:
    //

    // extract the sequence of unique variables
    val uniqOrderedVars = uniqueOrderedVariablesIn(orderedLiterals)

    info(uniqOrderedVars.mkString(", "))

    // ordered unique variables to corresponding domain sizes
    val varDomains = uniqOrderedVars.map(v => constants(v.domain).size).toArray
    info("Domain sizes of the ordered unique variables: " + varDomains.mkString(", "))

    // The total number of terms is the sum of all arities
    val numTerms = orderedLiterals.map(_.arity).sum

    // mask 1: contains 1 for the first time that a new variable appears, 0 otherwise.
    val mask1 = new Array[Int](numTerms)

    var uniqueVariables = Set[Variable]()
    var index = 0
    for (literal <- orderedLiterals; term <- literal.sentence.terms) {
      term match {
        case v: Variable if !uniqueVariables.contains(v) =>
          uniqueVariables += v
          mask1(index) = 1

        case c: Constant => mask1(index) = 0

        case _ => mask1(index) = 0
      }
      index += 1

    }

    info("mask1: " + mask1.mkString(", "))



    uniqueVariables = Set[Variable]()
    // mask 2: contains the number of new variable appearances for each literal
    val mask2 = orderedLiterals.map(
      _.sentence.variables.count {
        case v: Variable if !uniqueVariables.contains(v) =>
          uniqueVariables += v
          true
        case _ => false
      }
    )

    info("mask2: " + mask2.mkString(", "))


    //
    // Cartesian products
    //

    def cart1(source: Array[Int]): Int = {
      val indexes = util.Arrays.copyOf(source.map(_ - 1), source.length)
      val factors = util.Arrays.copyOf(indexes, indexes.length)



      var stop = false
      var idx = 0
      var counter = 0
      var iterations = 0
      var outerIterations = 0

      val startTime = System.currentTimeMillis()
      while (stop || idx != indexes.length) {
        outerIterations += 1
        stop = false
        idx = 0
        while (!stop && (idx < factors.length)) {
          iterations += 1
          if (factors(idx) > 0) {
            if (idx > 0) System.arraycopy(indexes, 0, factors, 0, idx)
            factors(idx) -= 1
            stop = true
          }
          else idx += 1
        }
        counter += 1


      }
      val endTime = System.currentTimeMillis()
      info("cart1 --- total number of groundings: " + counter)
      info("cart1 --- total number of iterations: " + iterations)
      info("cart1 --- total number of outer iterations: " + outerIterations)
      info("cart1 --- total number of inner iterations: " + (iterations - outerIterations))
      info("cart1 --- total time:" + Utilities.msecTimeToText(endTime - startTime))

      counter
    }

    def cart2(source: Array[Int]): Int = {
      // cartesian factors: current domain indexes for each unique ordered variable
      val indexes = util.Arrays.copyOf(source.map(_ - 1), source.length)
      val factors = util.Arrays.copyOf(indexes, indexes.length)

      val MAX = source.product


      val LAST_IDX = factors.length - 1
      var stop = false
      var idx = LAST_IDX
      var counter = 0
      //var iterations = 0
      //var outerIterations = 0
      //var copies = 0


      val startTime = System.currentTimeMillis()
      while (idx >= 0 && (counter < MAX)) {
        //println(counter + " : "+factors.mkString(", "))
        //outerIterations += 1
        stop = false
        idx = LAST_IDX

        while (!stop && idx >= 0) {
          //iterations += 1
          if (factors(idx) > 0) {
            factors(idx) -= 1
            stop = true

            val pos = idx + 1

            if (pos <= LAST_IDX)
              System.arraycopy(indexes, pos, factors, pos, LAST_IDX - pos + 1)
          }
          else idx -= 1
        }
        counter += 1


      }
      val endTime = System.currentTimeMillis()
      info("cart2 --- total number of groundings: " + counter + " of " + MAX)
      /*info("cart2 --- total number of iterations: " + iterations)
      info("cart2 --- total number of outer iterations: " + outerIterations)
      info("cart2 --- total number of inner iterations: " + (iterations - outerIterations))*/
      info("cart2 --- total time:" + Utilities.msecTimeToText(endTime - startTime))
      //info("cart2 --- total copies:" + copies)

      counter
    }

    /*cart1(varDomains)
    cart2(varDomains)
    info("REVERSED CART1")
    cart1(varDomains.reverse)
    info("REVERSED CART2")
    cart2(varDomains.reverse)*/

    /*val iterator = CartesianIterator.mkArithmetic(varDomains.map(_ - 1))
    val startTime = System.currentTimeMillis()
    val total = iterator.size
    val endTime = System.currentTimeMillis()
    info("iterator.size:=" + total)
    info("arithemtic iterator total time:" + Utilities.msecTimeToText(endTime - startTime))



    val iteratorRev = CartesianIterator.mkArithmetic(varDomains.reverse.map(_ - 1))
    val startTime2 = System.currentTimeMillis()
    val totalRev = iteratorRev.size
    val endTime2 = System.currentTimeMillis()
    info("iterator.size:=" + totalRev)
    info("arithemtic iterator total time:" + Utilities.msecTimeToText(endTime2 - startTime2))*/

    cart2(varDomains)
    //cart2(varDomains.reverse)

    // Note: the given domains should always be above zero
    /*val domainList = List(
      Array(10, 5, 2),
      Array(10, 1, 2),
      Array(1, 1, 10),
      Array(1, 10),
      Array(5, 10),
      Array(10),
      Array(1)
    )

    require(domainList.forall(_.forall(_ > 0)))

    for (domain <- domainList; (l, iteration) <- domain.permutations.zipWithIndex) {
      val elements = l.map(_ - 1)
      val expectedIterations = l.product // this is the correct number of products
      describe("Cartesian product of domains [" + elements.map(_.toString).reduceLeft(_ + ", " + _) + "]") {

        val result = cart2(l)
        info("iteration: " + iteration + "\n" +
          "\telements = [" + elements.map(_.toString).reduceLeft(_ + ", " + _) + "]\n" +
          "\texpected = " + expectedIterations + "\n" +
          "\tproduced = " + result)

        it("produces " + expectedIterations + " distinct Cartesian products") {
          assert(expectedIterations == result)
        }
      }
    }*/

  }


}
