package org.springframework.ide.vscode.boot.java.rewrite;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.Validated;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;
import org.openrewrite.maven.MavenParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ide.vscode.commons.java.IClasspath;
import org.springframework.ide.vscode.commons.java.IClasspathUtil;
import org.springframework.ide.vscode.commons.java.IJavaProject;
import org.springframework.ide.vscode.commons.languageserver.java.JavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;

public class RewriteRecipeRepository {
		
	private static final Logger log = LoggerFactory.getLogger(RewriteRecipeRepository.class);
	private static final String WORKSPACE_EXECUTE_COMMAND = "workspace/executeCommand";

	final private SimpleLanguageServer server;
	
	final private Map<String, Recipe> recipes;
	final private List<Recipe> globalCommandRecipes;

	final private JavaProjectFinder projectFinder;
	
	final public CompletableFuture<Void> loaded;
	
	private static final Set<String> TOP_LEVEL_RECIPES = Set.of(
			"org.openrewrite.java.spring.boot2.SpringBoot2JUnit4to5Migration",
			"org.openrewrite.java.spring.boot2.SpringBoot2BestPractices",
			"org.openrewrite.java.spring.boot2.SpringBoot1To2Migration",
			"org.openrewrite.java.testing.junit5.JUnit5BestPractices",
			"org.openrewrite.java.testing.junit5.JUnit4to5Migration",
			"org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_6"
	);
	
	public RewriteRecipeRepository(SimpleLanguageServer server, JavaProjectFinder projectFinder) {
		this.server = server;
		this.projectFinder = projectFinder;
		this.recipes = new HashMap<>();
		this.globalCommandRecipes = new ArrayList<>();
		this.loaded = CompletableFuture.runAsync(this::loadRecipes);
	}
	
	private void loadRecipes() {
		for (Recipe r : Environment.builder().scanRuntimeClasspath().build().listRecipes()) {
			if (r.getName() != null) {
				if (recipes.containsKey(r.getName())) {
					log.error("Duplicate ids: '" + r.getName() + "'");
				}
				recipes.put(r.getName(), r);
				if (r.getName().contains("spring")) {
					log.info("Recipe: " + r.getName());
				}
				
				if (TOP_LEVEL_RECIPES.contains(r.getName())) {
					Validated validation = Validated.invalid(null, null, null);
					try {
						validation = r.validate();
					} catch (Exception e) {
						// ignore
					}
					if (validation.isValid()) {
						globalCommandRecipes.add(r);
					}
				}
			}
		}
		server.doOnInitialized(() -> registerCommands());			
	}
	
	public Optional<Recipe> getRecipe(String name) {
		return Optional.of(recipes.get(name));
	}
	
	private void registerCommands() {
		log.info("Registering commands...");
		
		Builder<Object> listBuilder = ImmutableList.builder();
				
		server.onCommand("sts/rewrite/list", params -> {
			JsonElement uri = (JsonElement) params.getArguments().get(0);
			return CompletableFuture.completedFuture(uri == null ? Collections.emptyList() : listProjectRefactoringCommands(uri.getAsString()));
		});
		listBuilder.add("sts/rewrite/list");
		
		for (Recipe r : globalCommandRecipes) {
			listBuilder.add(createGlobalCommand(r));
		}
		
		String registrationId = UUID.randomUUID().toString();
		RegistrationParams params = new RegistrationParams(ImmutableList.of(
				new Registration(registrationId,
						WORKSPACE_EXECUTE_COMMAND,
						ImmutableMap.of("commands", listBuilder.build())
				)
		));
				
		server.getClient().registerCapability(params).thenAccept((v) -> {
			server.onShutdown(() -> server.getClient().unregisterCapability(new UnregistrationParams(List.of(new Unregistration(registrationId, WORKSPACE_EXECUTE_COMMAND)))));
		});
		
	}
	
	private String createGlobalCommand(Recipe r) {
		String commandId = "sts/rewrite/recipe/" + r.getName();
		server.onCommand(commandId, params -> {
			
			return CompletableFuture.supplyAsync(() -> {
				JsonElement uri = (JsonElement) params.getArguments().get(0);
				server.getProgressService().progressEvent(r.getName(), r.getDisplayName() + ": initiated...");
				return projectFinder.find(new TextDocumentIdentifier(uri.getAsString()));
			}).thenCompose(p -> {
				if (p.isPresent()) {
					return CompletableFuture.completedFuture(apply(r, p.get())).thenCompose(we -> {
						if (we.isPresent()) {
							server.getProgressService().progressEvent(r.getName(),
									r.getDisplayName() + ": applying document changes...");
							return server.getClient().applyEdit(new ApplyWorkspaceEditParams(we.get())).thenCompose(res -> {
								if (res.isApplied()) {
									server.getProgressService().progressEvent(r.getName(), null);
									return CompletableFuture.completedFuture("success");
								} else {
									server.getProgressService().progressEvent(r.getName(), null);
									return CompletableFuture.completedFuture(null);
								}
							});
						}
						return CompletableFuture.completedFuture(null);
					});
				}
				return CompletableFuture.completedFuture(null);
			});
			
//			JsonElement uri = (JsonElement) params.getArguments().get(0);
//			server.getProgressService().progressEvent(r.getName(), r.getDisplayName() + ": initiated...");
//			Optional<IJavaProject> foundProject = projectFinder.find(new TextDocumentIdentifier(uri.getAsString()));
//			if (foundProject.isPresent()) {
//				try {
//					IJavaProject project = foundProject.get();
//					Path absoluteProjectDir = Paths.get(project.getLocationUri());
//					server.getProgressService().progressEvent(r.getName(), r.getDisplayName() + ": parsing files...");
//					MavenProjectParser projectParser = createRewriteMavenParser(absoluteProjectDir,
//							new InMemoryExecutionContext());
//					List<SourceFile> sources = projectParser.parse(absoluteProjectDir, getClasspathEntries(project));
//					server.getProgressService().progressEvent(r.getName(),
//							r.getDisplayName() + ": computing changes...");
//					List<Result> results = r.run(sources);
//
//					Optional<WorkspaceEdit> we = ORDocUtils.createWorkspaceEdit(absoluteProjectDir, documents, results);
//					if (we.isPresent()) {
//						server.getProgressService().progressEvent(r.getName(),
//								r.getDisplayName() + ": applying document changes...");
//						return server.getClient().applyEdit(new ApplyWorkspaceEditParams(we.get())).thenCompose(res -> {
//							if (res.isApplied()) {
//								server.getProgressService().progressEvent(r.getName(), null);
//								return CompletableFuture.completedFuture("success");
//							} else {
//								server.getProgressService().progressEvent(r.getName(), null);
//								return CompletableFuture.completedFuture(null);
//							}
//						});
//					}
//				} catch (Exception e) {
//					server.getProgressService().progressEvent(r.getName(), null);
//					throw e;
//				}
//			}
//			server.getProgressService().progressEvent(r.getName(), null);
//			return CompletableFuture.completedFuture("nothing");
		});
		return commandId;
	}
	
	private Optional<WorkspaceEdit> apply(Recipe r, IJavaProject project) {
		Path absoluteProjectDir = Paths.get(project.getLocationUri());
		server.getProgressService().progressEvent(r.getName(), r.getDisplayName() + ": parsing files...");
		MavenProjectParser projectParser = createRewriteMavenParser(absoluteProjectDir,
				new InMemoryExecutionContext());
		List<SourceFile> sources = projectParser.parse(absoluteProjectDir, getClasspathEntries(project));
		server.getProgressService().progressEvent(r.getName(),
				r.getDisplayName() + ": computing changes...");
		List<Result> results = r.run(sources);
		return ORDocUtils.createWorkspaceEdit(absoluteProjectDir, server.getTextDocumentService(), results);
	}
	
	private List<RecipeDescriptor> listProjectRefactoringCommands(String uri) {
		if (uri != null) {
			Optional<IJavaProject> projectOpt = projectFinder.find(new TextDocumentIdentifier(uri));
			if (projectOpt.isPresent()) {
				List<RecipeDescriptor> commandDescriptors = new ArrayList<>(globalCommandRecipes.size()); 
				for (Recipe r : globalCommandRecipes) {
					commandDescriptors.add(new RecipeDescriptor(r.getName(), r.getDisplayName(), r.getDescription()));
				}
				return commandDescriptors;
			}
		}
		return Collections.emptyList();
	}
	
    private static MavenProjectParser createRewriteMavenParser(Path absoluteProjectDir, ExecutionContext context) {
        MavenParser.Builder mavenParserBuilder = MavenParser.builder()
                .mavenConfig(absoluteProjectDir.resolve(".mvn/maven.config"));

        MavenProjectParser mavenProjectParser = new MavenProjectParser(
                mavenParserBuilder,
                JavaParser.fromJavaVersion(),
                context
        );
        return mavenProjectParser;
    }
    
	private static List<Path> getClasspathEntries(IJavaProject project) {
		if (project == null) {
			return List.of();
		} else {
			IClasspath classpath = project.getClasspath();
			Stream<File> classpathEntries = IClasspathUtil.getAllBinaryRoots(classpath).stream();
			return classpathEntries
					.filter(file -> file.exists())
					.filter(file -> file.getName().endsWith(".jar"))
					.map(file -> file.getAbsoluteFile().toPath()).collect(Collectors.toList());
		}
	}

	@SuppressWarnings("unused")
	private static class RecipeDescriptor {
		String id;
		String label;
		String description;
		public RecipeDescriptor(String id, String label, String description) {
			this.id = id;
			this.label = label;
			this.description = description;
		}		
	}

}
