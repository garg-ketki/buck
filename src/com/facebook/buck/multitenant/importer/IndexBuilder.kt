/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.multitenant.importer

import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTarget
import com.facebook.buck.core.model.RuleType
import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode
import com.facebook.buck.multitenant.service.BuildPackage
import com.facebook.buck.multitenant.service.Changes
import com.facebook.buck.multitenant.service.Index
import com.facebook.buck.multitenant.service.RawBuildRule
import com.facebook.buck.rules.visibility.VisibilityPattern
import com.facebook.buck.util.json.ObjectMappers
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Ultimately, we would like to use kotlinx.serialization for this, but we are currently blocked by
 * https://youtrack.jetbrains.com/issue/KT-30998.
 */
fun populateIndexFromStream(index: Index, stream: InputStream): List<String> {
    val parser = ObjectMappers.createParser(stream)
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)

    val jsonNode = parser.readValueAsTree<JsonNode>()
    val commits = mutableListOf<String>()
    for (commit in jsonNode.asSequence()) {
        val hash = commit.get("commit").asText()
        val added = toBuildPackages(index, commit.get("added"))
        val modified = toBuildPackages(index, commit.get("modified"))
        var removed = toRemovedPackages(commit.get("removed"))
        val changes = Changes(added, modified, removed)
        index.addCommitData(hash, changes)
        commits.add(hash)
    }
    return commits
}

/** Parses an "ordinary" fully-qualified build target with no cells or flavors. */
fun parseOrdinaryBuildTarget(target: String): UnconfiguredBuildTarget {
    if (!target.startsWith("//")) {
        throw IllegalArgumentException("target '${target}' did not start with //")
    }

    val index = target.lastIndexOf(':')
    if (index < 0) {
        throw IllegalArgumentException("target '${target}' did not contain a colon")
    }

    return ImmutableUnconfiguredBuildTarget.of(
            "", target.substring(0, index), target.substring(index + 1), UnconfiguredBuildTarget.NO_FLAVORS
    )
}

val FAKE_RULE_TYPE = RuleTypeFactory.createBuildRule("fake_rule")

private fun toBuildPackages(index: Index, node: JsonNode?): List<BuildPackage> {
    if (node == null) {
        return listOf()
    }

    val buildPackages = mutableListOf<BuildPackage>()
    for (buildPackageItem in node) {
        val path = buildPackageItem.get("path").asText()
        val rulesAttr = buildPackageItem.get("rules")
        val rules = mutableSetOf<RawBuildRule>()
        for (rule in rulesAttr.elements()) {
            val name = rule.get("name").asText()
            val ruleType = rule.get("buck.type").asText()
            val deps = mutableSetOf<String>()
            val depsAttr = rule.get("deps")
            if (depsAttr != null) {
                deps.addAll(depsAttr.asSequence().map { it.asText() })
            }
            val buildTarget = index.buildTargetParser("//${path}:${name}")
            rules.add(createRawRule(index, buildTarget, ruleType, deps))
        }
        buildPackages.add(BuildPackage(Paths.get(path), rules))
    }

    return buildPackages
}

private fun toRemovedPackages(node: JsonNode?): List<Path> {
    if (node == null) {
        return listOf()
    }

    return node.asSequence().map { Paths.get(it.asText()) }.toList()
}

private fun createRawRule(index: Index, target: UnconfiguredBuildTarget, ruleType: String, deps: Set<String>): RawBuildRule {
    val node = ServiceRawTargetNode(target, RuleTypeFactory.createBuildRule(ruleType), ImmutableMap.of())
    return RawBuildRule(node, deps.map { index.buildTargetParser(it) }.toSet())
}

/**
 * Simplified implementation of [RawTargetNode] that is sufficient for the multitenant service's
 * needs.
 */
data class ServiceRawTargetNode(
        private val buildTarget: UnconfiguredBuildTarget,
        private val ruleType: RuleType,
        private val attributes: ImmutableMap<String, Any>) : RawTargetNode {

    override fun getBuildTarget(): UnconfiguredBuildTarget = buildTarget

    override fun getRuleType(): RuleType = ruleType

    override fun getAttributes(): ImmutableMap<String, Any>? = attributes

    override fun getVisibilityPatterns(): ImmutableSet<VisibilityPattern> {
        return ImmutableSet.of()
    }

    override fun getWithinViewPatterns(): ImmutableSet<VisibilityPattern> {
        return ImmutableSet.of()
    }
}
