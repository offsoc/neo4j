/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.rewriting.conditions

import org.neo4j.cypher.internal.expressions.NodePattern
import org.neo4j.cypher.internal.expressions.RelationshipPattern
import org.neo4j.cypher.internal.expressions.ShortestPathExpression
import org.neo4j.cypher.internal.rewriting.ValidatingCondition
import org.neo4j.cypher.internal.util.CancellationChecker
import org.neo4j.cypher.internal.util.Foldable.FoldableAny
import org.neo4j.cypher.internal.util.Foldable.SkipChildren

case object noUnnamedNodesAndRelationships extends ValidatingCondition {

  override def apply(that: Any)(cancellationChecker: CancellationChecker): Seq[String] = {
    that.folder(cancellationChecker).treeFold(Seq.empty[String]) {
      // We do not name nodes in relationships in shortest path expression
      case _: ShortestPathExpression => acc => SkipChildren(acc)
      case rel @ RelationshipPattern(None, _, _, _, _, _) => acc =>
          SkipChildren(acc :+ s"RelationshipPattern at ${rel.position} is unnamed")
      case node @ NodePattern(None, _, _, _) => acc =>
          SkipChildren(acc :+ s"NodePattern at ${node.position} is unnamed")
    }
  }

  override def name: String = productPrefix
}
