/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
package org.neo4j.cypher.internal.compiler.v2_2.planner.logical

import org.neo4j.cypher.internal.commons.CypherFunSuite
import org.neo4j.cypher.internal.compiler.v2_2.planner.logical.plans._
import org.neo4j.cypher.internal.compiler.v2_2.ast._
import org.neo4j.cypher.internal.compiler.v2_2.planner.{PlannerQuery, LogicalPlanningTestSupport2}
import org.neo4j.cypher.internal.compiler.v2_2.{PropertyKeyId, LabelId}
import org.neo4j.cypher.internal.compiler.v2_2.planner.BeLikeMatcher._
import org.neo4j.cypher.internal.compiler.v2_2.commands.{ManyQueryExpression, SingleQueryExpression}

class LeafPlanningIntegrationTest extends CypherFunSuite with LogicalPlanningTestSupport2 {

  test("should build plans for all nodes scans") {
    (new given {
      cardinality = mapCardinality {
        case _: AllNodesScan => 1
        case _               => Double.MaxValue
      }
    } planFor "MATCH (n) RETURN n").plan should equal(
      AllNodesScan("n", Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for label scans without compile-time label id") {
    (new given {
      cardinality = mapCardinality {
        case _: AllNodesScan => 1000
        case _: NodeByIdSeek => 2
        case _: NodeByLabelScan => 1
        case _ => Double.MaxValue
      }
    } planFor "MATCH (n:Awesome) RETURN n").plan should equal(
      NodeByLabelScan("n", Left("Awesome"), Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for label scans with compile-time label id") {
    implicit val plan = new given {
      cardinality = mapCardinality {
        case _: AllNodesScan => 1000
        case _: NodeByIdSeek => 2
        case _: NodeByLabelScan => 1
        case _ => Double.MaxValue
      }
      knownLabels = Set("Awesome")
    } planFor "MATCH (n:Awesome) RETURN n"

    plan.plan should equal(
      NodeByLabelScan("n", Right(labelId("Awesome")), Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for index seek when there is an index on the property") {
    implicit val plan = new given {
      indexOn("Awesome", "prop")
    } planFor "MATCH (n:Awesome) WHERE n.prop = 42 RETURN n"

    plan.plan should equal(
      NodeIndexSeek(
        "n",
        LabelToken("Awesome", LabelId(0)),
        PropertyKeyToken(PropertyKeyName("prop")_, PropertyKeyId(0)),
        ManyQueryExpression(Collection(Seq(SignedDecimalIntegerLiteral("42")_))_),
        Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for unique index seek when there is an unique index on the property") {
    implicit val plan = new given {
      uniqueIndexOn("Awesome", "prop")
    } planFor "MATCH (n:Awesome) WHERE n.prop = 42 RETURN n"

    plan.plan should equal(
      NodeIndexUniqueSeek("n", LabelToken("Awesome", LabelId(0)), PropertyKeyToken("prop", PropertyKeyId(0)), ManyQueryExpression(Collection(Seq(SignedDecimalIntegerLiteral("42")_))_), Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for unique index seek when there is both unique and non-unique indexes to pick") {
    implicit val plan = new given {
      indexOn("Awesome", "prop")
      uniqueIndexOn("Awesome", "prop")
    } planFor "MATCH (n:Awesome) WHERE n.prop = 42 RETURN n"

    plan.plan should equal(
      NodeIndexUniqueSeek("n", LabelToken("Awesome", LabelId(0)), PropertyKeyToken("prop", PropertyKeyId(1)), ManyQueryExpression(Collection(Seq(SignedDecimalIntegerLiteral("42")_))_), Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for node by ID mixed with label scan when node by ID is cheaper") {
    (new given {
      knownLabels = Set("Awesome")
    } planFor "MATCH (n:Awesome) WHERE id(n) = 42 RETURN n").plan should equal (
      Selection(
        List(HasLabels(Identifier("n")_, Seq(LabelName("Awesome")_))_),
        NodeByIdSeek("n", EntityByIdExprs(Seq(SignedDecimalIntegerLiteral("42")_)), Set.empty)(PlannerQuery.empty)
      )(PlannerQuery.empty)
    )
  }

  test("should build plans for node by ID when the predicate is IN and rhs is a param") {
    (new given {
      knownLabels = Set("Awesome")
    } planFor "MATCH (n:Awesome) WHERE id(n) IN {param} RETURN n").plan should equal (
      Selection(
        List(HasLabels(Identifier("n")_, Seq(LabelName("Awesome")_))_),
        NodeByIdSeek("n", EntityByIdParameter(Parameter("param")_), Set.empty)(PlannerQuery.empty)
      )(PlannerQuery.empty)
    )
  }

  test("should build plans for directed rel by ID when the predicate is IN and rhs is a param") {
    (new given {
    } planFor "MATCH (a)-[r]->(b) WHERE id(r) IN {param} RETURN a, r, b").plan should equal (
      DirectedRelationshipByIdSeek("r", EntityByIdParameter(Parameter("param")_), "a", "b", Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for undirected rel by ID when the predicate is IN and rhs is a param") {
    (new given {
    } planFor "MATCH (a)-[r]-(b) WHERE id(r) IN {param} RETURN a, r, b").plan should equal (
      UndirectedRelationshipByIdSeek("r", EntityByIdParameter(Parameter("param")_), "a", "b", Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for node by ID when the predicate is IN") {
    (new given {
      knownLabels = Set("Awesome")
    } planFor "MATCH (n:Awesome) WHERE id(n) IN [42, 64] RETURN n").plan should equal (
      Selection(
        List(HasLabels(Identifier("n")_, Seq(LabelName("Awesome")_))_),
        NodeByIdSeek("n", EntityByIdExprs(Seq(SignedDecimalIntegerLiteral("42")_, SignedDecimalIntegerLiteral("64")_)), Set.empty)(PlannerQuery.empty)
      )(PlannerQuery.empty)
    )
  }

  test("should build plans for index seek when there is an index on the property and an IN predicate") {
    (new given {
      indexOn("Awesome", "prop")
    } planFor "MATCH (n:Awesome) WHERE n.prop IN [42] RETURN n").plan should beLike {
      case NodeIndexSeek(
              IdName("n"),
              LabelToken("Awesome", _),
              PropertyKeyToken("prop", _),
              ManyQueryExpression(Collection(_)), _) => ()
    }
  }

  test("should not use indexes for large collections") {
    // Index selectivity is 0.02, and label selectivity is 0.2.
    // The OR query will use the formula A ∪ B = ¬ ( ¬ A ∩ ¬ B ) to calculate the selectivities,
    // which gives us the formula:
    // 1 - (1 - 0.02)^15 = 1 - .98^10 = 1 - .713702596 = 0.286297404 which is less selective than .2, so the index
    // should not win
    (new given {
      indexOn("Awesome", "prop")
    } planFor "MATCH (n:Awesome) WHERE n.prop IN [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15] RETURN n").plan should beLike {
      case _: Selection => ()
    }
  }

  // FIX: 2014-09-15 Fix selectivity of unique indices by tracking number of indexed entities in statistics model
  test("should use indexes for large collections if it is a unique index") {
    (new given {
      uniqueIndexOn("Awesome", "prop")
    } planFor "MATCH (n:Awesome) WHERE n.prop IN [1,2,3,4,5,6,7,8,9,10] RETURN n").plan should beLike {
      case _: NodeIndexUniqueSeek => ()
    }
  }

  test("should build plans for label scans when a hint is given") {
    implicit val plan = new given planFor "MATCH (n:Foo:Bar:Baz) USING SCAN n:Bar RETURN n"

    plan.plan should equal(
      Selection(
        Seq(HasLabels(ident("n"), Seq(LabelName("Foo")_))_, HasLabels(ident("n"), Seq(LabelName("Baz")_))_),
        NodeByLabelScan("n", Left("Bar"), Set.empty)(PlannerQuery.empty)
      )(PlannerQuery.empty)
    )
  }

  test("should build plans for index seek when there is an index on the property and a hint is given") {
    implicit val plan = new given {
      indexOn("Awesome", "prop")
    } planFor "MATCH (n) USING INDEX n:Awesome(prop) WHERE n:Awesome AND n.prop = 42 RETURN n"

    plan.plan should equal(
      NodeIndexSeek("n", LabelToken("Awesome", LabelId(0)), PropertyKeyToken("prop", PropertyKeyId(0)), ManyQueryExpression(Collection(Seq(SignedDecimalIntegerLiteral("42")_))_), Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for index seek when there is an index on the property and a hint is given when returning *") {
    implicit val plan = new given {
      indexOn("Awesome", "prop")
    } planFor "MATCH (n) USING INDEX n:Awesome(prop) WHERE n:Awesome AND n.prop = 42 RETURN *"

    plan.plan should equal(
      NodeIndexSeek("n", LabelToken("Awesome", LabelId(0)), PropertyKeyToken("prop", PropertyKeyId(0)), ManyQueryExpression(Collection(Seq(SignedDecimalIntegerLiteral("42")_))_), Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for index seek when there are multiple indices on properties and a hint is given") {
    implicit val plan = new given {
      indexOn("Awesome", "prop1")
      indexOn("Awesome", "prop2")
    } planFor "MATCH (n) USING INDEX n:Awesome(prop2) WHERE n:Awesome AND n.prop1 = 42 and n.prop2 = 3 RETURN n "

    plan.plan should equal(
      Selection(
        List(In(Property(ident("n"), PropertyKeyName("prop1")_)_, Collection(Seq(SignedDecimalIntegerLiteral("42")_))_)_),
        NodeIndexSeek("n", LabelToken("Awesome", LabelId(0)), PropertyKeyToken("prop2", PropertyKeyId(1)), ManyQueryExpression(Collection(Seq(SignedDecimalIntegerLiteral("3")_))_), Set.empty)(PlannerQuery.empty)
      )(PlannerQuery.empty)
    )
  }

  test("should build plans for unique index seek when there is an unique index on the property and a hint is given") {
    implicit val plan = new given {
      uniqueIndexOn("Awesome", "prop")
    } planFor "MATCH (n) USING INDEX n:Awesome(prop) WHERE n:Awesome AND n.prop = 42 RETURN n"

    plan.plan should equal(
      NodeIndexUniqueSeek("n", LabelToken("Awesome", LabelId(0)), PropertyKeyToken("prop", PropertyKeyId(0)), ManyQueryExpression(Collection(Seq(SignedDecimalIntegerLiteral("42")_))_), Set.empty)(PlannerQuery.empty)
    )
  }

  test("should build plans for unique index seek when there are multiple unique indices on properties and a hint is given") {
    implicit val plan = new given {
      uniqueIndexOn("Awesome", "prop1")
      uniqueIndexOn("Awesome", "prop2")
    } planFor "MATCH (n) USING INDEX n:Awesome(prop2) WHERE n:Awesome AND n.prop1 = 42 and n.prop2 = 3 RETURN n"

    plan.plan should equal(
      Selection(
        List(In(Property(ident("n"), PropertyKeyName("prop1")_)_, Collection(Seq(SignedDecimalIntegerLiteral("42")_))_)_),
        NodeIndexUniqueSeek("n", LabelToken("Awesome", LabelId(0)), PropertyKeyToken("prop2", PropertyKeyId(1)), ManyQueryExpression(Collection(Seq(SignedDecimalIntegerLiteral("3")_))_), Set.empty)(PlannerQuery.empty)
      )(PlannerQuery.empty)
    )
  }

  test("should build plans for unique index seek using IN when there are multiple unique indices on properties and a hint is given") {
    implicit val plan = new given {
      uniqueIndexOn("Awesome", "prop1")
      uniqueIndexOn("Awesome", "prop2")
    } planFor "MATCH (n) USING INDEX n:Awesome(prop2) WHERE n:Awesome AND n.prop1 = 42 and n.prop2 IN [3] RETURN n"

    plan.plan should equal(
      Selection(
        List(In(Property(ident("n"), PropertyKeyName("prop1")_)_, Collection(Seq(SignedDecimalIntegerLiteral("42")_))_)_),
        NodeIndexUniqueSeek("n", LabelToken("Awesome", LabelId(0)), PropertyKeyToken("prop2", PropertyKeyId(1)), ManyQueryExpression(Collection(Seq(SignedDecimalIntegerLiteral("3")_))_), Set.empty)(PlannerQuery.empty)
      )(PlannerQuery.empty)
    )
  }
}
