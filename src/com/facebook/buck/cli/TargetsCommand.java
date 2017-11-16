/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.Dot;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.hashing.FilePathHashLoader;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.log.Logger;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.InMemoryBuildFileTree;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildState.SpeculativeParsing;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.ParallelRuleKeyCalculator;
import com.facebook.buck.rules.RuleDepsCache;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetGraphHashing;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class TargetsCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(TargetsCommand.class);

  // TODO(mbolin): Use org.kohsuke.args4j.spi.PathOptionHandler. Currently, we resolve paths
  // manually, which is likely the path to madness.
  @Option(
    name = "--referenced-file",
    aliases = {"--referenced_file"},
    usage = "The referenced file list, --referenced-file file1 file2  ... fileN --other_option",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> referencedFiles;

  @Option(
    name = "--detect-test-changes",
    usage =
        "Modifies the --referenced-file and --show-target-hash flags to pretend that "
            + "targets depend on their tests (experimental)"
  )
  private boolean isDetectTestChanges;

  @Option(
    name = "--type",
    usage = "The types of target to filter by, --type type1 type2 ... typeN --other_option",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> types;

  @Option(name = "--json", usage = "Print JSON representation of each target")
  private boolean json;

  @Option(
    name = "--dot",
    usage =
        "Output rule keys in DOT format, only works with both --show-rulekey and --show-transitive-rulekeys"
  )
  private boolean dot;

  @Option(name = "--print0", usage = "Delimit targets using the ASCII NUL character.")
  private boolean print0;

  @Option(
    name = "--resolve-alias",
    aliases = {"--resolvealias"},
    usage = "Print the fully-qualified build target for the specified alias[es]"
  )
  private boolean isResolveAlias;

  @Option(
    name = "--show-cell-path",
    aliases = {"--show_cell_path"},
    usage = "Print the absolute path of the cell for each rule after the rule name."
  )
  private boolean isShowCellPath;

  @Option(
    name = "--show-output",
    aliases = {"--show_output"},
    usage =
        "Print the path to the output, relative to the cell path, for each rule after the "
            + "rule name. Use '--show-full-output' to obtain the full absolute path."
  )
  private boolean isShowOutput;

  @Option(
    name = "--show-full-output",
    aliases = {"--show_full_output"},
    usage = "Print the absolute path to the output, for each rule after the rule name."
  )
  private boolean isShowFullOutput;

  @Option(
    name = "--show-rulekey",
    aliases = {"--show_rulekey"},
    forbids = {"--show-target-hash"},
    usage =
        "Print the RuleKey of each rule after the rule name. "
            + "Incompatible with '--show-target-hash'."
  )
  private boolean isShowRuleKey;

  @Option(
    name = "--show-transitive-rulekeys",
    aliases = {"--show-transitive-rulekeys"},
    usage = "Show rule keys of transitive deps as well."
  )
  private boolean isShowTransitiveRuleKeys;

  @Option(
    name = "--show-target-hash",
    forbids = {"--show-rulekey"},
    usage =
        "Print a stable hash of each target after the target name. "
            + "Incompatible with '--show-rulekey'."
  )
  private boolean isShowTargetHash;

  private enum TargetHashFileMode {
    PATHS_AND_CONTENTS,
    PATHS_ONLY,
  }

  @Option(
    name = "--target-hash-file-mode",
    usage =
        "Modifies computation of target hashes. If set to PATHS_AND_CONTENTS (the "
            + "default), the contents of all files referenced from the targets will be used to "
            + "compute the target hash. If set to PATHS_ONLY, only files' paths contribute to the "
            + "hash. See also --target-hash-modified-paths."
  )
  private TargetHashFileMode targetHashFileMode = TargetHashFileMode.PATHS_AND_CONTENTS;

  @Option(
    name = "--target-hash-modified-paths",
    usage =
        "Modifies computation of target hashes. Only effective when "
            + "--target-hash-file-mode is set to PATHS_ONLY. If a target or its dependencies "
            + "reference a file from this set, the target's hash will be different than if this "
            + "option was omitted. Otherwise, the target's hash will be the same as if this option "
            + "was omitted.",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> targetHashModifiedPaths;

  @Option(
    name = "--output-attributes",
    usage =
        "List of attributes to output, --output-attributes attr1 att2 ... attrN. "
            + "Attributes can be regular expressions. ",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> outputAttributes;

  @Nullable
  @Option(
    name = "--rulekeys-log-path",
    usage = "If set, log a binary representation of rulekeys to this file."
  )
  private String ruleKeyLogPath = null;

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  public ImmutableSet<String> getTypes() {
    return types.get();
  }

  public PathArguments.ReferencedFiles getReferencedFiles(Path projectRoot) throws IOException {
    return PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, referencedFiles.get());
  }

  /** @return {@code true} if {@code --detect-test-changes} was specified. */
  public boolean isDetectTestChanges() {
    return isDetectTestChanges;
  }

  public boolean getPrintJson() {
    return json;
  }

  public boolean isPrint0() {
    return print0;
  }

  /** @return {@code true} if {@code --resolve-alias} was specified. */
  public boolean isResolveAlias() {
    return isResolveAlias;
  }

  /** @return {@code true} if {@code --show-cell-path} was specified. */
  public boolean isShowCellPath() {
    return isShowCellPath;
  }

  /** @return {@code true} if {@code --show-output} was specified. */
  public boolean isShowOutput() {
    return isShowOutput;
  }

  /** @return {@code true} if {@code --show-output} was specified. */
  public boolean isShowFullOutput() {
    return isShowFullOutput;
  }

  /** @return {@code true} if {@code --show-rulekey} was specified. */
  public boolean isShowRuleKey() {
    return isShowRuleKey;
  }

  /** @return {@code true} if {@code --show-transitive-rulekeys} was specified. */
  public boolean isShowTransitiveRuleKeys() {
    return isShowTransitiveRuleKeys;
  }

  /** @return {@code true} if {@code --show-targethash} was specified. */
  public boolean isShowTargetHash() {
    return isShowTargetHash;
  }

  /** @return mode passed to {@code --target-hash-file-mode}. */
  public TargetHashFileMode getTargetHashFileMode() {
    return targetHashFileMode;
  }

  /** @return attributes pass in {@code --output-attributes}. */
  public ImmutableSet<String> getOutputAttributes() {
    return outputAttributes.get();
  }

  /**
   * Determines if the results should be in JSON format. This is true either when explicitly
   * required by the {@code --json} argument or when there is at least one item in the {@code
   * --output-attributes} arguments.
   *
   * <p>The {@code --output--attributes} arguments implicitly enables JSON format because there is
   * currently no way to output attributes in non-JSON format. Also, it keeps this command
   * consistent with the query command.
   */
  public boolean shouldUseJsonFormat() {
    return getPrintJson() || !getOutputAttributes().isEmpty();
  }

  /**
   * Determines if the results should be in DOT format. This is true when {@code --dot} parameter is
   * specified along with {@code --show-rulekey} and {@code --show-transitive-rulekeys}
   */
  private boolean shouldUseDotFormat() {
    return dot && isShowRuleKey() && isShowTransitiveRuleKeys();
  }

  /**
   * @return set of paths passed to {@code --assume-modified-files} if either {@code
   *     --assume-modified-files} or {@code --assume-no-modified-files} is used, absent otherwise.
   */
  public ImmutableSet<Path> getTargetHashModifiedPaths() {
    return targetHashModifiedPaths
        .get()
        .stream()
        .map(Paths::get)
        .collect(MoreCollectors.toImmutableSet());
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    try (CommandThreadManager pool =
        new CommandThreadManager("Targets", getConcurrencyLimit(params.getBuckConfig()))) {
      ListeningExecutorService executor = pool.getListeningExecutorService();

      // Exit early if --resolve-alias is passed in: no need to parse any build files.
      if (isResolveAlias()) {
        return ResolveAliasHelper.resolveAlias(
            params, executor, getEnableParserProfiling(), getArguments());
      }

      return runWithExecutor(params, executor);
    } catch (BuildTargetException | BuildFileParseException | CycleException | VersionException e) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }
  }

  private int runWithExecutor(CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
          CycleException, VersionException {
    Optional<ImmutableSet<Class<? extends Description<?>>>> descriptionClasses =
        getDescriptionClassFromParams(params);
    if (!descriptionClasses.isPresent()) {
      return 1;
    }

    // shortcut to old plain simple format
    if (!(isShowCellPath()
        || isShowOutput()
        || isShowFullOutput()
        || isShowRuleKey()
        || isShowTargetHash())) {
      return printResults(
          params,
          executor,
          getMatchingNodes(
              params, buildTargetGraphAndTargets(params, executor), descriptionClasses));
    }

    // shortcut to DOT format, it only works along with rule keys and transitive rule keys
    // because we want to construct action graph
    if (shouldUseDotFormat()) {
      return printDotFormat(params, executor);
    }

    // plain or json output
    ImmutableMap<BuildTarget, TargetResult> showRulesResult;
    TargetGraphAndBuildTargets targetGraphAndBuildTargetsForShowRules =
        buildTargetGraphAndTargetsForShowRules(params, executor, descriptionClasses);
    boolean useVersioning =
        isShowRuleKey() || isShowOutput() || isShowFullOutput()
            ? params.getBuckConfig().getBuildVersions()
            : params.getBuckConfig().getTargetsVersions();
    targetGraphAndBuildTargetsForShowRules =
        useVersioning
            ? toVersionedTargetGraph(params, targetGraphAndBuildTargetsForShowRules)
            : targetGraphAndBuildTargetsForShowRules;
    showRulesResult =
        computeShowRules(
            params,
            executor,
            new Pair<>(
                targetGraphAndBuildTargetsForShowRules.getTargetGraph(),
                targetGraphAndBuildTargetsForShowRules
                    .getTargetGraph()
                    .getAll(targetGraphAndBuildTargetsForShowRules.getBuildTargets())));

    if (shouldUseJsonFormat()) {
      ImmutableSortedSet.Builder<BuildTarget> keysBuilder = ImmutableSortedSet.naturalOrder();
      keysBuilder.addAll(showRulesResult.keySet());
      Iterable<TargetNode<?, ?>> matchingNodes =
          targetGraphAndBuildTargetsForShowRules.getTargetGraph().getAll(keysBuilder.build());
      printJsonForTargets(params, executor, matchingNodes, showRulesResult, getOutputAttributes());
    } else {
      printShowRules(showRulesResult, params);
    }

    return 0;
  }

  /**
   * Output rules along with dependencies as a graph in DOT format As a part of invocation,
   * constructs both target and action graphs
   */
  private int printDotFormat(CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
          VersionException {
    TargetGraphAndBuildTargets targetGraphAndTargets = buildTargetGraphAndTargets(params, executor);
    ActionGraphAndResolver result =
        params
            .getActionGraphCache()
            .getActionGraph(
                params.getBuckEventBus(),
                targetGraphAndTargets.getTargetGraph(),
                params.getBuckConfig(),
                params.getRuleKeyConfiguration());

    // construct real graph
    MutableDirectedGraph<BuildRule> actionGraphMutable = new MutableDirectedGraph<>();

    for (BuildRule rule : result.getActionGraph().getNodes()) {
      actionGraphMutable.addNode(rule);
      for (BuildRule node : rule.getBuildDeps()) {
        actionGraphMutable.addEdge(rule, node);
      }
    }

    // ruleKeyFactory is used to calculate rule key that we also want to display on a graph
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(result.getResolver());
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(
            new RuleKeyFieldLoader(params.getRuleKeyConfiguration()),
            params.getFileHashCache(),
            DefaultSourcePathResolver.from(ruleFinder),
            ruleFinder);

    // it is time to construct DOT output
    Dot.builder(new DirectedAcyclicGraph<>(actionGraphMutable), "action_graph")
        .setNodeToName(
            node ->
                node.getFullyQualifiedName()
                    + " "
                    + node.getType()
                    + " "
                    + ruleKeyFactory.build(node).toString())
        .setNodeToTypeName(node -> node.getType())
        .build()
        .writeOutput(params.getConsole().getStdOut());

    return 0;
  }

  private TargetGraphAndBuildTargets buildTargetGraphAndTargetsForShowRules(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      Optional<ImmutableSet<Class<? extends Description<?>>>> descriptionClasses)
      throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {
    if (getArguments().isEmpty()) {
      ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
      TargetGraphAndBuildTargets completeTargetGraphAndBuildTargets =
          params
              .getParser()
              .buildTargetGraphForTargetNodeSpecs(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  executor,
                  ImmutableList.of(
                      TargetNodePredicateSpec.of(
                          BuildFileSpec.fromRecursivePath(
                              Paths.get(""), params.getCell().getRoot()))),
                  parserConfig.getDefaultFlavorsMode());
      SortedMap<String, TargetNode<?, ?>> matchingNodes =
          getMatchingNodes(params, completeTargetGraphAndBuildTargets, descriptionClasses);

      Iterable<BuildTarget> buildTargets =
          FluentIterable.from(matchingNodes.values()).transform(TargetNode::getBuildTarget);

      return TargetGraphAndBuildTargets.builder()
          .setTargetGraph(completeTargetGraphAndBuildTargets.getTargetGraph())
          .setBuildTargets(buildTargets)
          .build();
    } else {
      return params
          .getParser()
          .buildTargetGraphForTargetNodeSpecs(
              params.getBuckEventBus(),
              params.getCell(),
              getEnableParserProfiling(),
              executor,
              parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), getArguments()));
    }
  }

  /** Print out matching targets in alphabetical order. */
  private int printResults(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      SortedMap<String, TargetNode<?, ?>> matchingNodes)
      throws BuildFileParseException {
    if (shouldUseJsonFormat()) {
      printJsonForTargets(
          params, executor, matchingNodes.values(), ImmutableMap.of(), getOutputAttributes());
    } else if (isPrint0()) {
      printNullDelimitedTargets(matchingNodes.keySet(), params.getConsole().getStdOut());
    } else {
      for (String target : matchingNodes.keySet()) {
        params.getConsole().getStdOut().println(target);
      }
    }
    return 0;
  }

  private SortedMap<String, TargetNode<?, ?>> getMatchingNodes(
      CommandRunnerParams params,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      Optional<ImmutableSet<Class<? extends Description<?>>>> descriptionClasses)
      throws IOException {
    PathArguments.ReferencedFiles referencedFiles =
        getReferencedFiles(params.getCell().getFilesystem().getRootPath());
    SortedMap<String, TargetNode<?, ?>> matchingNodes;
    // If all of the referenced files are paths outside the project root, then print nothing.
    if (!referencedFiles.absolutePathsOutsideProjectRootOrNonExistingPaths.isEmpty()
        && referencedFiles.relativePathsUnderProjectRoot.isEmpty()) {
      matchingNodes = ImmutableSortedMap.of();
    } else {
      ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);

      ImmutableSet<BuildTarget> matchingBuildTargets = targetGraphAndBuildTargets.getBuildTargets();
      matchingNodes =
          getMatchingNodes(
              targetGraphAndBuildTargets.getTargetGraph(),
              referencedFiles.relativePathsUnderProjectRoot.isEmpty()
                  ? Optional.empty()
                  : Optional.of(referencedFiles.relativePathsUnderProjectRoot),
              matchingBuildTargets.isEmpty() ? Optional.empty() : Optional.of(matchingBuildTargets),
              descriptionClasses.get().isEmpty() ? Optional.empty() : descriptionClasses,
              isDetectTestChanges(),
              parserConfig.getBuildFileName());
    }
    return matchingNodes;
  }

  private TargetGraphAndBuildTargets buildTargetGraphAndTargets(
      CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
          VersionException {
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    // Parse the entire action graph, or (if targets are specified), only the specified targets and
    // their dependencies. If we're detecting test changes we need the whole graph as tests are not
    // dependencies.
    TargetGraphAndBuildTargets targetGraphAndBuildTargets;
    if (getArguments().isEmpty() || isDetectTestChanges()) {
      targetGraphAndBuildTargets =
          TargetGraphAndBuildTargets.builder()
              .setBuildTargets(ImmutableSet.of())
              .setTargetGraph(
                  params
                      .getParser()
                      .buildTargetGraphForTargetNodeSpecs(
                          params.getBuckEventBus(),
                          params.getCell(),
                          getEnableParserProfiling(),
                          executor,
                          ImmutableList.of(
                              TargetNodePredicateSpec.of(
                                  BuildFileSpec.fromRecursivePath(
                                      Paths.get(""), params.getCell().getRoot()))),
                          parserConfig.getDefaultFlavorsMode())
                      .getTargetGraph())
              .build();
    } else {
      targetGraphAndBuildTargets =
          params
              .getParser()
              .buildTargetGraphForTargetNodeSpecs(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  executor,
                  parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), getArguments()),
                  parserConfig.getDefaultFlavorsMode());
    }
    return params.getBuckConfig().getTargetsVersions()
        ? toVersionedTargetGraph(params, targetGraphAndBuildTargets)
        : targetGraphAndBuildTargets;
  }

  @SuppressWarnings("unchecked")
  private Optional<ImmutableSet<Class<? extends Description<?>>>> getDescriptionClassFromParams(
      CommandRunnerParams params) {
    ImmutableSet<String> types = getTypes();
    ImmutableSet.Builder<Class<? extends Description<?>>> descriptionClassesBuilder =
        ImmutableSet.builder();
    for (String name : types) {
      try {
        KnownBuildRuleTypes knownBuildRuleTypes =
            params.getKnownBuildRuleTypesProvider().get(params.getCell());
        BuildRuleType type = knownBuildRuleTypes.getBuildRuleType(name);
        Description<?> description = knownBuildRuleTypes.getDescription(type);
        descriptionClassesBuilder.add((Class<? extends Description<?>>) description.getClass());
      } catch (IllegalArgumentException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe("Invalid build rule type: " + name));
        return Optional.empty();
      }
    }
    return Optional.of(descriptionClassesBuilder.build());
  }

  private void printShowRules(
      Map<BuildTarget, TargetResult> showRulesResult, CommandRunnerParams params) {
    for (Entry<BuildTarget, TargetResult> entry :
        ImmutableSortedMap.copyOf(showRulesResult).entrySet()) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.add(entry.getKey().getFullyQualifiedName());
      TargetResult targetResult = entry.getValue();
      targetResult.getRuleKey().ifPresent(builder::add);
      if (isShowCellPath()) {
        builder.add(entry.getKey().getCellPath().toString());
      }
      targetResult.getOutputPath().ifPresent(builder::add);
      targetResult.getGeneratedSourcePath().ifPresent(builder::add);
      targetResult.getTargetHash().ifPresent(builder::add);
      params.getConsole().getStdOut().println(Joiner.on(' ').join(builder.build()));
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  /**
   * @param graph Graph used to resolve dependencies between targets and find all build files.
   * @param referencedFiles If present, the result will be limited to the nodes that transitively
   *     depend on at least one of those.
   * @param matchingBuildTargets If present, the result will be limited to the specified targets.
   * @param descriptionClasses If present, the result will be limited to targets with the specified
   *     types.
   * @param detectTestChanges If true, tests are considered to be dependencies of the targets they
   *     are testing.
   * @return A map of target names to target nodes.
   */
  @VisibleForTesting
  ImmutableSortedMap<String, TargetNode<?, ?>> getMatchingNodes(
      TargetGraph graph,
      Optional<ImmutableSet<Path>> referencedFiles,
      final Optional<ImmutableSet<BuildTarget>> matchingBuildTargets,
      final Optional<ImmutableSet<Class<? extends Description<?>>>> descriptionClasses,
      boolean detectTestChanges,
      String buildFileName) {
    ImmutableSet<TargetNode<?, ?>> directOwners;
    if (referencedFiles.isPresent()) {
      BuildFileTree buildFileTree =
          new InMemoryBuildFileTree(
              graph
                  .getNodes()
                  .stream()
                  .map(TargetNode::getBuildTarget)
                  .collect(MoreCollectors.toImmutableSet()));
      directOwners =
          graph
              .getNodes()
              .stream()
              .filter(new DirectOwnerPredicate(buildFileTree, referencedFiles.get(), buildFileName))
              .collect(MoreCollectors.toImmutableSet());
    } else {
      directOwners = graph.getNodes();
    }
    Iterable<TargetNode<?, ?>> selectedReferrers =
        FluentIterable.from(getDependentNodes(graph, directOwners, detectTestChanges))
            .filter(
                targetNode -> {
                  if (matchingBuildTargets.isPresent()
                      && !matchingBuildTargets.get().contains(targetNode.getBuildTarget())) {
                    return false;
                  }

                  if (descriptionClasses.isPresent()
                      && !descriptionClasses
                          .get()
                          .contains(targetNode.getDescription().getClass())) {
                    return false;
                  }

                  return true;
                });
    ImmutableSortedMap.Builder<String, TargetNode<?, ?>> matchingNodesBuilder =
        ImmutableSortedMap.naturalOrder();
    for (TargetNode<?, ?> targetNode : selectedReferrers) {
      matchingNodesBuilder.put(targetNode.getBuildTarget().getFullyQualifiedName(), targetNode);
    }
    return matchingNodesBuilder.build();
  }

  /**
   * @param graph A graph used to resolve dependencies between targets.
   * @param nodes A set of nodes.
   * @param detectTestChanges If true, tests are considered to be dependencies of the targets they
   *     are testing.
   * @return A set of all nodes that transitively depend on {@code nodes} (a superset of {@code
   *     nodes}).
   */
  private static ImmutableSet<TargetNode<?, ?>> getDependentNodes(
      final TargetGraph graph, ImmutableSet<TargetNode<?, ?>> nodes, boolean detectTestChanges) {
    ImmutableMultimap.Builder<TargetNode<?, ?>, TargetNode<?, ?>> extraEdgesBuilder =
        ImmutableMultimap.builder();

    if (detectTestChanges) {
      for (TargetNode<?, ?> node : graph.getNodes()) {
        if (node.getConstructorArg() instanceof HasTests) {
          ImmutableSortedSet<BuildTarget> tests = ((HasTests) node.getConstructorArg()).getTests();
          for (BuildTarget testTarget : tests) {
            Optional<TargetNode<?, ?>> testNode = graph.getOptional(testTarget);
            if (!testNode.isPresent()) {
              throw new HumanReadableException(
                  "'%s' (test of '%s') is not in the target graph.", testTarget, node);
            }
            extraEdgesBuilder.put(testNode.get(), node);
          }
        }
      }
    }
    final ImmutableMultimap<TargetNode<?, ?>, TargetNode<?, ?>> extraEdges =
        extraEdgesBuilder.build();

    final ImmutableSet.Builder<TargetNode<?, ?>> builder = ImmutableSet.builder();
    AbstractBreadthFirstTraversal<TargetNode<?, ?>> traversal =
        new AbstractBreadthFirstTraversal<TargetNode<?, ?>>(nodes) {
          @Override
          public Iterable<TargetNode<?, ?>> visit(TargetNode<?, ?> targetNode) {
            builder.add(targetNode);
            return FluentIterable.from(graph.getIncomingNodesFor(targetNode))
                .append(extraEdges.get(targetNode))
                .toSet();
          }
        };
    traversal.start();
    return builder.build();
  }

  @Override
  public String getShortDescription() {
    return "prints the list of buildable targets";
  }

  @VisibleForTesting
  void printJsonForTargets(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      Iterable<TargetNode<?, ?>> targetNodes,
      ImmutableMap<BuildTarget, TargetResult> targetResults,
      ImmutableSet<String> outputAttributes)
      throws BuildFileParseException {
    PatternsMatcher attributesPatternsMatcher = new PatternsMatcher(outputAttributes);

    // Print the JSON representation of the build node for the specified target(s).
    params.getConsole().getStdOut().println("[");

    Iterator<TargetNode<?, ?>> targetNodeIterator = targetNodes.iterator();

    try (PerBuildState state =
        new PerBuildState(
            params.getParser(),
            params.getBuckEventBus(),
            executor,
            params.getCell(),
            params.getKnownBuildRuleTypesProvider(),
            getEnableParserProfiling(),
            SpeculativeParsing.DISABLED)) {

      while (targetNodeIterator.hasNext()) {
        TargetNode<?, ?> targetNode = targetNodeIterator.next();
        Map<String, Object> rawTargetNode =
            params.getParser().getRawTargetNode(state, params.getCell(), targetNode);
        if (rawTargetNode == null) {
          params
              .getConsole()
              .printErrorText(
                  "unable to find rule for target "
                      + targetNode.getBuildTarget().getFullyQualifiedName());
          continue;
        }

        TargetResult targetResult = targetResults.get(targetNode.getBuildTarget());
        if (targetResult != null) {
          for (TargetResultFieldName field : TargetResultFieldName.values()) {
            field
                .getter
                .apply(targetResult)
                .ifPresent(value -> rawTargetNode.put(field.name, value));
          }
        }
        rawTargetNode.put(
            "fully_qualified_name", targetNode.getBuildTarget().getFullyQualifiedName());
        if (isShowCellPath()) {
          rawTargetNode.put("buck.cell_path", targetNode.getBuildTarget().getCellPath());
        }

        // Print the build rule information as JSON.
        StringWriter stringWriter = new StringWriter();
        try {
          ObjectMappers.WRITER
              .withDefaultPrettyPrinter()
              .writeValue(
                  stringWriter, attributesPatternsMatcher.filterMatchingMapKeys(rawTargetNode));
        } catch (IOException e) {
          // Shouldn't be possible while writing to a StringWriter...
          throw new RuntimeException(e);
        }
        params.getConsole().getStdOut().print(stringWriter.getBuffer().toString());
        if (targetNodeIterator.hasNext()) {
          params.getConsole().getStdOut().print(',');
        }
        params.getConsole().getStdOut().println();
      }
    }

    params.getConsole().getStdOut().println("]");
  }

  @VisibleForTesting
  static void printNullDelimitedTargets(Iterable<String> targets, PrintStream printStream) {
    for (String target : targets) {
      printStream.print(target + '\0');
    }
  }

  /**
   * Create a {@link ThriftRuleKeyLogger} depending on whether {@link TargetsCommand#ruleKeyLogPath}
   * is set or not
   */
  private Optional<ThriftRuleKeyLogger> createRuleKeyLogger() throws IOException {
    if (ruleKeyLogPath == null) {
      return Optional.empty();
    } else {
      return Optional.of(ThriftRuleKeyLogger.create(Paths.get(ruleKeyLogPath)));
    }
  }

  /**
   * Assumes at least one target is specified. Computes each of the specified targets, followed by
   * the rule key, output path, and/or target hash, depending on what flags are passed in.
   *
   * @return An immutable map consisting of result of show options for to each target rule
   */
  private ImmutableMap<BuildTarget, TargetResult> computeShowRules(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      Pair<TargetGraph, Iterable<TargetNode<?, ?>>> targetGraphAndTargetNodes)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
          CycleException {

    TargetResultBuilders targetResultBuilders = new TargetResultBuilders();
    if (isShowTargetHash()) {
      computeShowTargetHash(params, executor, targetGraphAndTargetNodes, targetResultBuilders);
    }

    // We only need the action graph if we're showing the output or the keys, and the
    // RuleKeyFactory if we're showing the keys.
    Optional<ActionGraph> actionGraph;
    Optional<BuildRuleResolver> buildRuleResolver;
    Optional<ParallelRuleKeyCalculator<RuleKey>> ruleKeyCalculator = Optional.empty();

    try (ThriftRuleKeyLogger ruleKeyLogger = createRuleKeyLogger().orElse(null)) {
      if (isShowRuleKey() || isShowOutput() || isShowFullOutput()) {
        ActionGraphAndResolver result =
            params
                .getActionGraphCache()
                .getActionGraph(
                    params.getBuckEventBus(),
                    targetGraphAndTargetNodes.getFirst(),
                    params.getBuckConfig(),
                    params.getRuleKeyConfiguration());
        actionGraph = Optional.of(result.getActionGraph());
        buildRuleResolver = Optional.of(result.getResolver());
        if (isShowRuleKey()) {
          SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(result.getResolver());
          // Setup a parallel rule key calculator to use when building rule keys.
          ruleKeyCalculator =
              Optional.of(
                  new ParallelRuleKeyCalculator<>(
                      executor,
                      new DefaultRuleKeyFactory(
                          new RuleKeyFieldLoader(params.getRuleKeyConfiguration()),
                          params.getFileHashCache(),
                          DefaultSourcePathResolver.from(ruleFinder),
                          ruleFinder,
                          Optional.ofNullable(ruleKeyLogger)),
                      new RuleDepsCache(buildRuleResolver.get()),
                      (eventBus, rule) -> () -> {}));
        }
      } else {
        actionGraph = Optional.empty();
        buildRuleResolver = Optional.empty();
      }

      // Start rule calculations in parallel.
      for (TargetNode<?, ?> targetNode : targetGraphAndTargetNodes.getSecond()) {
        if (actionGraph.isPresent() && isShowRuleKey()) {
          BuildRule rule = buildRuleResolver.get().requireRule(targetNode.getBuildTarget());
          ruleKeyCalculator.get().calculate(params.getBuckEventBus(), rule);
        }
      }

      for (TargetNode<?, ?> targetNode : targetGraphAndTargetNodes.getSecond()) {
        TargetResult.Builder builder =
            targetResultBuilders.getOrCreate(targetNode.getBuildTarget());
        Preconditions.checkNotNull(builder);
        if (actionGraph.isPresent() && isShowRuleKey()) {
          BuildRule rule = buildRuleResolver.get().requireRule(targetNode.getBuildTarget());
          builder.setRuleKey(
              Futures.getUnchecked(
                      ruleKeyCalculator.get().calculate(params.getBuckEventBus(), rule))
                  .toString());
          if (isShowTransitiveRuleKeys()) {
            ParallelRuleKeyCalculator<RuleKey> calculator = ruleKeyCalculator.get();
            AbstractBreadthFirstTraversal.traverse(
                rule,
                traversedRule -> {
                  TargetResult.Builder showOptionsBuilder =
                      targetResultBuilders.getOrCreate(traversedRule.getBuildTarget());
                  showOptionsBuilder.setRuleKey(
                      Futures.getUnchecked(
                              calculator.calculate(params.getBuckEventBus(), traversedRule))
                          .toString());
                  return traversedRule.getBuildDeps();
                });
          }
        }
      }

      for (Map.Entry<BuildTarget, TargetResult.Builder> entry :
          targetResultBuilders.map.entrySet()) {
        BuildTarget target = entry.getKey();
        TargetResult.Builder builder = entry.getValue();
        if (buildRuleResolver.isPresent()) {
          BuildRule rule = buildRuleResolver.get().requireRule(target);
          builder.setRuleType(rule.getType());
          if (isShowOutput() || isShowFullOutput()) {
            getUserFacingOutputPath(
                    DefaultSourcePathResolver.from(
                        new SourcePathRuleFinder(buildRuleResolver.get())),
                    rule,
                    params.getBuckConfig().getBuckOutCompatLink())
                .map(
                    path ->
                        isShowFullOutput()
                            ? path
                            : params.getCell().getFilesystem().relativize(path))
                .ifPresent(path -> builder.setOutputPath(path.toString()));
            // If the output dir is requested, also calculate the generated src dir
            if (rule instanceof JavaLibrary) {
              ((JavaLibrary) rule)
                  .getGeneratedSourcePath()
                  .map(
                      path -> {
                        final Path rootPath = params.getCell().getFilesystem().getRootPath();
                        Path sameFsPath = rootPath.resolve(path.toString());
                        Path returnPath =
                            isShowFullOutput() ? path : rootPath.relativize(sameFsPath);
                        return returnPath.toString();
                      })
                  .ifPresent(builder::setGeneratedSourcePath);
            }
          }
        }
      }

      ImmutableMap.Builder<BuildTarget, TargetResult> builder = new ImmutableMap.Builder<>();
      for (Map.Entry<BuildTarget, TargetResult.Builder> entry :
          targetResultBuilders.map.entrySet()) {
        builder.put(entry.getKey(), entry.getValue().build());
      }
      return builder.build();
    }
  }

  /** Returns absolute path to the output rule, if the rule has an output. */
  static Optional<Path> getUserFacingOutputPath(
      SourcePathResolver pathResolver, BuildRule rule, boolean buckOutCompatLink) {
    Optional<Path> outputPathOptional =
        Optional.ofNullable(rule.getSourcePathToOutput()).map(pathResolver::getRelativePath);

    // When using buck out compat mode, we favor using the default buck output path in the UI, so
    // amend the output paths when this is set.
    if (outputPathOptional.isPresent() && buckOutCompatLink) {
      BuckPaths paths = rule.getProjectFilesystem().getBuckPaths();
      if (outputPathOptional.get().startsWith(paths.getConfiguredBuckOut())) {
        outputPathOptional =
            Optional.of(
                paths
                    .getBuckOut()
                    .resolve(
                        outputPathOptional
                            .get()
                            .subpath(
                                paths.getConfiguredBuckOut().getNameCount(),
                                outputPathOptional.get().getNameCount())));
      }
    }

    return outputPathOptional.map(rule.getProjectFilesystem()::resolve);
  }

  private Pair<TargetGraph, Iterable<TargetNode<?, ?>>> computeTargetsAndGraphToShowTargetHash(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      Pair<TargetGraph, Iterable<TargetNode<?, ?>>> targetGraphAndTargetNodes)
      throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {

    if (isDetectTestChanges()) {
      ImmutableSet<BuildTarget> explicitTestTargets =
          TargetGraphAndTargets.getExplicitTestTargets(
              targetGraphAndTargetNodes
                  .getFirst()
                  .getSubgraph(targetGraphAndTargetNodes.getSecond())
                  .getNodes()
                  .iterator());
      LOG.debug("Got explicit test targets: %s", explicitTestTargets);

      Iterable<BuildTarget> matchingBuildTargetsWithTests =
          mergeBuildTargets(targetGraphAndTargetNodes.getSecond(), explicitTestTargets);

      // Parse the BUCK files for the tests of the targets passed in from the command line.
      TargetGraph targetGraphWithTests =
          params
              .getParser()
              .buildTargetGraph(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  executor,
                  matchingBuildTargetsWithTests);

      return new Pair<>(
          targetGraphWithTests, targetGraphWithTests.getAll(matchingBuildTargetsWithTests));
    } else {
      return targetGraphAndTargetNodes;
    }
  }

  private Iterable<BuildTarget> mergeBuildTargets(
      Iterable<TargetNode<?, ?>> targetNodes, Iterable<BuildTarget> buildTargets) {
    ImmutableSet.Builder<BuildTarget> targetsBuilder = ImmutableSet.builder();

    targetsBuilder.addAll(buildTargets);

    for (TargetNode<?, ?> node : targetNodes) {
      targetsBuilder.add(node.getBuildTarget());
    }
    return targetsBuilder.build();
  }

  private FileHashLoader createOrGetFileHashLoader(CommandRunnerParams params) throws IOException {
    TargetHashFileMode targetHashFileMode = getTargetHashFileMode();
    switch (targetHashFileMode) {
      case PATHS_AND_CONTENTS:
        return params.getFileHashCache();
      case PATHS_ONLY:
        return new FilePathHashLoader(
            params.getCell().getRoot(),
            getTargetHashModifiedPaths(),
            params.getCell().getBuckConfig().getView(ParserConfig.class).getAllowSymlinks()
                != ParserConfig.AllowSymlinks.FORBID);
    }
    throw new IllegalStateException(
        "Invalid value for target hash file mode: " + targetHashFileMode);
  }

  private void computeShowTargetHash(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      Pair<TargetGraph, Iterable<TargetNode<?, ?>>> targetGraphAndTargetNodes,
      TargetResultBuilders resultBuilders)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException,
          CycleException {
    LOG.debug("Getting target hash for %s", targetGraphAndTargetNodes.getSecond());

    Pair<TargetGraph, Iterable<TargetNode<?, ?>>> targetGraphAndNodesWithTests =
        computeTargetsAndGraphToShowTargetHash(params, executor, targetGraphAndTargetNodes);

    TargetGraph targetGraphWithTests = targetGraphAndNodesWithTests.getFirst();

    FileHashLoader fileHashLoader = createOrGetFileHashLoader(params);

    // Hash each target's rule description and contents of any files.
    ImmutableMap<BuildTarget, HashCode> buildTargetHashes =
        new TargetGraphHashing(
                params.getBuckEventBus(),
                targetGraphWithTests,
                fileHashLoader,
                targetGraphAndNodesWithTests.getSecond())
            .setNumThreads(params.getBuckConfig().getNumThreads())
            .hashTargetGraph();

    ImmutableMap<BuildTarget, HashCode> finalHashes =
        rehashWithTestsIfNeeded(
            targetGraphWithTests, targetGraphAndTargetNodes.getSecond(), buildTargetHashes);

    for (TargetNode<?, ?> targetNode : targetGraphAndTargetNodes.getSecond()) {
      BuildTarget buildTarget = targetNode.getBuildTarget();
      resultBuilders
          .getOrCreate(buildTarget)
          .setTargetHash(getHashCodeOrThrow(finalHashes, buildTarget).toString());
    }
  }

  private ImmutableMap<BuildTarget, HashCode> rehashWithTestsIfNeeded(
      final TargetGraph targetGraphWithTests,
      Iterable<TargetNode<?, ?>> inputTargets,
      ImmutableMap<BuildTarget, HashCode> buildTargetHashes)
      throws CycleException {

    if (!isDetectTestChanges()) {
      return buildTargetHashes;
    }

    AcyclicDepthFirstPostOrderTraversal<TargetNode<?, ?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(
            node -> targetGraphWithTests.getAll(node.getParseDeps()).iterator());

    Map<BuildTarget, HashCode> hashesWithTests = new HashMap<>();

    for (TargetNode<?, ?> node : traversal.traverse(inputTargets)) {
      hashNodeWithDependencies(buildTargetHashes, hashesWithTests, node);
    }

    return ImmutableMap.copyOf(hashesWithTests);
  }

  private void hashNodeWithDependencies(
      ImmutableMap<BuildTarget, HashCode> buildTargetHashes,
      Map<BuildTarget, HashCode> hashesWithTests,
      TargetNode<?, ?> node) {
    HashCode nodeHashCode = getHashCodeOrThrow(buildTargetHashes, node.getBuildTarget());

    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putBytes(nodeHashCode.asBytes());

    Iterable<BuildTarget> dependentTargets = node.getParseDeps();
    LOG.debug("Hashing target %s with dependent nodes %s", node, dependentTargets);
    for (BuildTarget targetToHash : dependentTargets) {
      HashCode dependencyHash = getHashCodeOrThrow(hashesWithTests, targetToHash);
      hasher.putBytes(dependencyHash.asBytes());
    }

    if (isDetectTestChanges()) {
      for (BuildTarget targetToHash :
          Preconditions.checkNotNull(TargetNodes.getTestTargetsForNode(node))) {
        HashCode testNodeHashCode = getHashCodeOrThrow(buildTargetHashes, targetToHash);
        hasher.putBytes(testNodeHashCode.asBytes());
      }
    }
    hashesWithTests.put(node.getBuildTarget(), hasher.hash());
  }

  private static HashCode getHashCodeOrThrow(
      Map<BuildTarget, HashCode> buildTargetHashCodes, BuildTarget buildTarget) {
    HashCode hashCode = buildTargetHashCodes.get(buildTarget);
    return Preconditions.checkNotNull(hashCode, "Cannot find hash for target: %s", buildTarget);
  }

  private static class DirectOwnerPredicate implements Predicate<TargetNode<?, ?>> {

    private final ImmutableSet<Path> referencedInputs;
    private final ImmutableSet<Path> basePathOfTargets;
    private final String buildFileName;

    /**
     * @param referencedInputs A {@link TargetNode} must reference at least one of these paths as
     *     input to match the predicate. All the paths must be relative to the project root. Ignored
     *     if empty.
     */
    public DirectOwnerPredicate(
        BuildFileTree buildFileTree, ImmutableSet<Path> referencedInputs, String buildFileName) {
      this.referencedInputs = referencedInputs;

      ImmutableSet.Builder<Path> basePathOfTargetsBuilder = ImmutableSet.builder();
      for (Path input : referencedInputs) {
        buildFileTree.getBasePathOfAncestorTarget(input).ifPresent(basePathOfTargetsBuilder::add);
      }
      this.basePathOfTargets = basePathOfTargetsBuilder.build();
      this.buildFileName = buildFileName;
    }

    @Override
    public boolean test(TargetNode<?, ?> node) {
      // For any referenced file, only those with the nearest target base path can
      // directly depend on that file.
      if (!basePathOfTargets.contains(node.getBuildTarget().getBasePath())) {
        return false;
      }

      for (Path input : node.getInputs()) {
        for (Path referencedInput : referencedInputs) {
          if (referencedInput.startsWith(input)) {
            return true;
          }
        }
      }

      return referencedInputs.contains(node.getBuildTarget().getBasePath().resolve(buildFileName));
    }
  }

  /**
   * Holds the output values for a build target. Handlers for different output types will accumulate
   * their results in to result builder for each build target.
   */
  private static class TargetResultBuilders {
    final Map<BuildTarget, TargetResult.Builder> map = new HashMap<>();

    TargetResult.Builder getOrCreate(BuildTarget target) {
      return map.computeIfAbsent(target, ignored -> TargetResult.builder());
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractTargetResult {
    public abstract Optional<String> getOutputPath();

    public abstract Optional<String> getGeneratedSourcePath();

    public abstract Optional<String> getRuleKey();

    public abstract Optional<String> getTargetHash();

    public abstract Optional<String> getRuleType();
  }

  private enum TargetResultFieldName {
    OUTPUT_PATH("buck.outputPath", TargetResult::getOutputPath),
    GEN_SRC_PATH("buck.generatedSourcePath", TargetResult::getGeneratedSourcePath),
    TARGET_HASH("buck.targetHash", TargetResult::getTargetHash),
    RULE_KEY("buck.ruleKey", TargetResult::getRuleKey),
    RULE_TYPE("buck.ruleType", TargetResult::getRuleType);

    private String name;
    private Function<TargetResult, Optional<String>> getter;

    TargetResultFieldName(String name, Function<TargetResult, Optional<String>> getter) {
      this.name = name;
      this.getter = getter;
    }
  }
}
