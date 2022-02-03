package org.springframework.ide.vscode.boot.java.handlers;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.java.MethodMatcher;
import org.springframework.context.ApplicationContext;
import org.springframework.ide.vscode.boot.java.Annotations;
import org.springframework.ide.vscode.boot.java.rewrite.ConvertAutowiredParameterIntoConstructorParameter;
import org.springframework.ide.vscode.boot.java.rewrite.ORAstUtils;
import org.springframework.ide.vscode.boot.java.rewrite.ORCompilationUnitCache;
import org.springframework.ide.vscode.boot.java.rewrite.ORDocUtils;
import org.springframework.ide.vscode.boot.java.rewrite.RewriteRecipeRepository;
import org.springframework.ide.vscode.boot.java.rewrite.RewriteRefactorings;
import org.springframework.ide.vscode.boot.java.utils.CompilationUnitCache;
import org.springframework.ide.vscode.commons.java.IJavaProject;
import org.springframework.ide.vscode.commons.languageserver.java.JavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.quickfix.QuickfixEdit;
import org.springframework.ide.vscode.commons.languageserver.util.CodeActionHandler;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleTextDocumentService;
import org.springframework.ide.vscode.commons.util.text.IRegion;
import org.springframework.ide.vscode.commons.util.text.TextDocument;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

public class BootJavaCodeActionProvider implements CodeActionHandler {
	
	private static final String REMOVE_ALL_REQUEST_MAPPINGS = "RemoveAllRequestMappings";
	private static final String REMOVE_SINGLE_REQUEST_MAPPING = "RemoveSingleRequestMapping";
	private static final String CONVERT_AUTOWIRED_FIELD_INTO_CONSTRUCTOR_PARAMETER = "convertAutowiredFieldIntoConstructorParameter";
	public static final String REMOVE_UNNECESSARY_AUTOWIRED_FROM_CONSTRUCTOR = "RemoveUnnecessaryConstructorAutowired";
	
	final private JavaProjectFinder projectFinder;
	final private CompilationUnitCache cuCache;
	final private RewriteRefactorings rewriteRefactorings;
	final private RewriteRecipeRepository recipesRepo;
	final private ORCompilationUnitCache orCuCache;
	final private SimpleLanguageServer server;
	
	public BootJavaCodeActionProvider(SimpleLanguageServer server, JavaProjectFinder projectFinder, CompilationUnitCache cuCache, RewriteRefactorings rewriteRefactorings, RewriteRecipeRepository recipesRepo, ORCompilationUnitCache orCuCache) {
		this.server = server;
		this.orCuCache = orCuCache;
		this.projectFinder = projectFinder;
		this.cuCache = cuCache;
		this.rewriteRefactorings = rewriteRefactorings;
		this.recipesRepo = recipesRepo;
		
		this.recipesRepo.loaded.thenAccept(v -> initRewriteRefactorings());
	}

	private void initRewriteRefactorings() {
		
		rewriteRefactorings.addRefactoring(CONVERT_AUTOWIRED_FIELD_INTO_CONSTRUCTOR_PARAMETER, this::convertAutowiredFieldIntoConstructorParameter);
		
		recipesRepo.getRecipe("org.openrewrite.java.spring.NoRequestMappingAnnotation").ifPresent(r -> {
			rewriteRefactorings.addRefactoring(REMOVE_ALL_REQUEST_MAPPINGS, (appContext, args) -> removeAllRequestMappings(appContext, args, r));		
			rewriteRefactorings.addRefactoring(REMOVE_SINGLE_REQUEST_MAPPING, (appContext, args) -> removeSingleRequestMapping(appContext, args, r));
		});
		
		recipesRepo.getRecipe("org.openrewrite.java.spring.NoAutowiredOnConstructor").ifPresent(r -> {
//			rewriteRefactorings.addRefactoring(REMOVE_UNNECESSARY_AUTOWIRED_FROM_CONSTRUCTOR, (appContext, args) -> removeUnnecessaryAutowiredFromConstructor(args, r));
			server.getQuickfixRegistry().register(REMOVE_UNNECESSARY_AUTOWIRED_FROM_CONSTRUCTOR, p -> {
				if (p instanceof JsonElement) {
					List<String> l = new Gson().fromJson((JsonElement) p, List.class);
					return new QuickfixEdit(removeUnnecessaryAutowiredFromConstructor(l, r), null);
				}
				return null;
			});
		});
		
	}
	
	private WorkspaceEdit removeUnnecessaryAutowiredFromConstructor(List<?> args, Recipe r) {
		SimpleTextDocumentService documents = server.getTextDocumentService();
		String docUri = (String) args.get(0);
		String classFqName = (String) args.get(1);
		TextDocument doc = documents.getLatestSnapshot(docUri);
		
		Optional<IJavaProject> project = projectFinder.find(new TextDocumentIdentifier(docUri));
		
		if (project.isPresent()) {
			return removeUnnecessaryAutowiredFromConstructor(r, project.get(), doc, classFqName);
		}
		
		return null;
	}
	
	private WorkspaceEdit removeUnnecessaryAutowiredFromConstructor(Recipe r, IJavaProject project, TextDocument doc, String classFqName) {
		String docUri = doc.getId().getUri();
		return orCuCache.withCompilationUnit(project, URI.create(docUri), cu -> {
			if (cu == null) {
				throw new IllegalStateException("Cannot parse Java file: " + docUri);
			}
			List<Result> results = ORAstUtils.nodeRecipe(r, t -> {
				if (t instanceof org.openrewrite.java.tree.J.ClassDeclaration) {
					org.openrewrite.java.tree.J.ClassDeclaration c = (org.openrewrite.java.tree.J.ClassDeclaration) t;
					return c.getType() != null && classFqName.equals(c.getType().getFullyQualifiedName());
				}
				return false;
			}).run(List.of(cu));
			if (!results.isEmpty() && results.get(0).getAfter() != null) {
				Optional<TextDocumentEdit> edit = ORDocUtils.computeTextDocEdit(doc, results.get(0));
				return edit.map(e -> {
					WorkspaceEdit workspaceEdit = new WorkspaceEdit();
					workspaceEdit.setDocumentChanges(List.of(Either.forLeft(e)));
					return workspaceEdit;
				}).orElse(null);
			}
			
			return null;
		});
	}
	


	private WorkspaceEdit convertAutowiredFieldIntoConstructorParameter(ApplicationContext appContext, List<?> args) {
		SimpleTextDocumentService documents = server.getTextDocumentService();
		String docUri = (String) args.get(0);
		String classFqName = (String) args.get(1);
		String fieldName = (String) args.get(2);
		TextDocument doc = documents.getLatestSnapshot(docUri);
		
		Optional<IJavaProject> project = projectFinder.find(new TextDocumentIdentifier(docUri));
		
		if (project.isPresent()) {
			return orCuCache.withCompilationUnit(project.get(), URI.create(docUri), cu -> {
				if (cu == null) {
					throw new IllegalStateException("Cannot parse Java file: " + docUri);
				}
				List<Result> results = new ConvertAutowiredParameterIntoConstructorParameter(classFqName, fieldName).run(List.of(cu));
				if (!results.isEmpty() && results.get(0).getAfter() != null) {
					Optional<TextDocumentEdit> edit = ORDocUtils.computeTextDocEdit(doc, results.get(0));
					return edit.map(e -> {
						WorkspaceEdit workspaceEdit = new WorkspaceEdit();
						workspaceEdit.setDocumentChanges(List.of(Either.forLeft(e)));
						return workspaceEdit;
					}).orElse(null);
				}
				
				return null;
			});
		}
		return null;
	}
	
	private WorkspaceEdit removeAllRequestMappings(ApplicationContext appContext, List<?> args, Recipe r) {
		SimpleTextDocumentService documents = server.getTextDocumentService();
		String docUri = (String) args.get(0);
		TextDocument doc = documents.getLatestSnapshot(docUri);
		
		Optional<IJavaProject> project = projectFinder.find(new TextDocumentIdentifier(docUri));
		
		if (project.isPresent()) {
			return orCuCache.withCompilationUnit(project.get(), URI.create(docUri), cu -> {
				if (cu == null) {
					throw new IllegalStateException("Cannot parse Java file: " + docUri);
				}
				List<Result> results = r.run(List.of(cu));
				if (!results.isEmpty() && results.get(0).getAfter() != null) {
					Optional<TextDocumentEdit> edit = ORDocUtils.computeTextDocEdit(doc, results.get(0));
					return edit.map(e -> {
						WorkspaceEdit workspaceEdit = new WorkspaceEdit();
						workspaceEdit.setDocumentChanges(List.of(Either.forLeft(e)));
						return workspaceEdit;
					}).orElse(null);
				}
				
				return null;
			});
		}
		return null;
	}

	private WorkspaceEdit removeSingleRequestMapping(ApplicationContext appContext, List<?> args, Recipe r) {
		SimpleTextDocumentService documents = server.getTextDocumentService();
		String docUri = (String) args.get(0);
		String matchStr = (String) args.get(1);
		TextDocument doc = documents.getLatestSnapshot(docUri);
		
		Optional<IJavaProject> project = projectFinder.find(new TextDocumentIdentifier(docUri));
		
		if (project.isPresent()) {
			return orCuCache.withCompilationUnit(project.get(), URI.create(docUri), cu -> {
				if (cu == null) {
					throw new IllegalStateException("Cannot parse Java file: " + docUri);
				}
				MethodMatcher macther = new MethodMatcher(matchStr);
				List<Result> results = ORAstUtils.nodeRecipe(r, t -> {
					if (t instanceof org.openrewrite.java.tree.J.MethodDeclaration) {
						org.openrewrite.java.tree.J.MethodDeclaration m = (org.openrewrite.java.tree.J.MethodDeclaration) t;
						return macther.matches(m.getMethodType());
					}
					return false;
				}).run(List.of(cu));
				if (!results.isEmpty() && results.get(0).getAfter() != null) {
					Optional<TextDocumentEdit> edit = ORDocUtils.computeTextDocEdit(doc, results.get(0));
					return edit.map(e -> {
						WorkspaceEdit workspaceEdit = new WorkspaceEdit();
						workspaceEdit.setDocumentChanges(List.of(Either.forLeft(e)));
						return workspaceEdit;
					}).orElse(null);
				}
				
				return null;
			});
		}
		return null;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<Either<Command, CodeAction>> handle(CancelChecker cancelToken, TextDocument doc, IRegion region) {
		Optional<IJavaProject> project = projectFinder.find(doc.getId());
		if (project.isPresent()) {
			return cuCache.withCompilationUnit(project.get(), URI.create(doc.getId().getUri()), cu -> {
				ASTNode found = NodeFinder.perform(cu, region.getOffset(), region.getLength());
				ASTNode node = found;
				List<Either<Command, CodeAction>> codeActions = new ArrayList<>();
				
				for (; node != null && !(node instanceof FieldDeclaration); node = node.getParent()) {
					// nothing
				}
				if (node instanceof FieldDeclaration) {
					FieldDeclaration fd = (FieldDeclaration) node;
					
					if (fd.fragments().size() == 1) {
						Optional<Annotation> autowired = fd.modifiers().stream().filter(Annotation.class::isInstance).map(Annotation.class::cast).filter(a -> {
							IAnnotationBinding binding = ((Annotation)a).resolveAnnotationBinding();
							if (binding != null && binding.getAnnotationType() != null ) {
								return Annotations.AUTOWIRED.equals(binding.getAnnotationType().getQualifiedName());
							}
							return false;
						}).findFirst();
						
						if (autowired.isPresent()) {
							CodeAction ca = new CodeAction();
							ca.setKind(CodeActionKind.Refactor);
							ca.setTitle("Convert into Constructor Parameter");
							
							ca.setData(new RewriteRefactorings.Data(CONVERT_AUTOWIRED_FIELD_INTO_CONSTRUCTOR_PARAMETER, List.of(
									doc.getId().getUri(),
									((TypeDeclaration) fd.getParent()).resolveBinding().getQualifiedName(),
									((VariableDeclarationFragment)fd.fragments().get(0)).getName().getIdentifier())));

							codeActions.add(Either.forRight(ca));
						}
					}
					
				}
				
				node = found;
				for (; node != null && !(node instanceof Annotation); node = node.getParent()) {
					// nothing
				}
				if (node instanceof Annotation) {
					Annotation a = (Annotation) node;
					ITypeBinding type = a.resolveTypeBinding();
					if (type != null && Annotations.SPRING_REQUEST_MAPPING.equals(type.getQualifiedName())) {
						if (a.getParent() instanceof MethodDeclaration) {
							MethodDeclaration method = (MethodDeclaration) a.getParent();
							CodeAction ca = new CodeAction();
							ca.setKind(CodeActionKind.Refactor);
							ca.setTitle("Replace all @RequestMapping with @GetMapping etc.");
							
							ca.setData(new RewriteRefactorings.Data(REMOVE_ALL_REQUEST_MAPPINGS, List.of(
									doc.getId().getUri()
							)));

							codeActions.add(Either.forRight(ca));
							
							String methodMatcher = "* " + method.getName().getIdentifier() + "(*)";
							IMethodBinding methodBinding = method.resolveBinding();
							if (methodBinding != null) {
								StringBuilder sb = new StringBuilder(methodBinding.getDeclaringClass().getQualifiedName());
								sb.append(' ');
								sb.append(methodBinding.getName());
								sb.append('(');
								sb.append(Arrays.stream(methodBinding.getParameterTypes()).map(b -> {
									if (b.isParameterizedType() ) {
										return b.getErasure().getQualifiedName();
									}
									return b.getQualifiedName();
								}).collect(Collectors.joining(","))); 
								sb.append(')');
								methodMatcher = sb.toString();
							}
							
							ca = new CodeAction();
							ca.setKind(CodeActionKind.Refactor);
							ca.setTitle("Replace single @RequestMapping with @GetMapping etc.");
							
							ca.setData(new RewriteRefactorings.Data(REMOVE_SINGLE_REQUEST_MAPPING, List.of(
									doc.getId().getUri(),
									methodMatcher
							)));

							codeActions.add(Either.forRight(ca));
						}
					}
										
				}
				
//				node = found;
//				for (; node != null && !(node instanceof Annotation); node = node.getParent()) {
//					// nothing
//				}
//				if (node instanceof Annotation) {
//					Annotation a = (Annotation) node;
//					ITypeBinding type = a.resolveTypeBinding();
//					if (type != null && Annotations.AUTOWIRED.equals(type.getQualifiedName())) {
//						if (a.getParent() instanceof MethodDeclaration) {
//							MethodDeclaration method = (MethodDeclaration) a.getParent();
//							IMethodBinding methodBinding = method.resolveBinding();
//							if (methodBinding != null) {
//								ITypeBinding declaringType = methodBinding.getDeclaringClass();
//								if (declaringType != null && isOnlyOneConstructor(declaringType)) {
//									CodeAction ca = new CodeAction();
//									ca.setKind(CodeActionKind.Refactor);
//									ca.setTitle("Remove unnecessary @Autowired");
//									
//									ca.setData(new RewriteRefactorings.Data(REMOVE_UNNECESSARY_AUTOWIRED_FROM_CONSTRUCTOR, List.of(
//											doc.getId().getUri(),
//											declaringType.getQualifiedName()
//									)));
//
//									codeActions.add(Either.forRight(ca));
//								}
//							}
//						}
//					}
//				}


				
				return codeActions;
			});
		}
		return Collections.emptyList();
	}
	
	private boolean isOnlyOneConstructor(ITypeBinding t) {
		int numberOfConstructors = 0;
		if (!t.isInterface()) {
			for (IMethodBinding m : t.getDeclaredMethods()) {
				if (m.isConstructor()) {
					numberOfConstructors++;
					if (numberOfConstructors > 1) {
						break;
					}
				}
			}
		}
		return numberOfConstructors == 1;
	}

}
