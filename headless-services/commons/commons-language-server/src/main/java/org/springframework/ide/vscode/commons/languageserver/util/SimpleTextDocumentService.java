/*******************************************************************************
 * Copyright (c) 2016, 2022 VMware Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.commons.languageserver.util;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.ide.vscode.commons.languageserver.config.LanguageServerProperties;
import org.springframework.ide.vscode.commons.languageserver.quickfix.Quickfix;
import org.springframework.ide.vscode.commons.util.Assert;
import org.springframework.ide.vscode.commons.util.BadLocationException;
import org.springframework.ide.vscode.commons.util.text.LanguageId;
import org.springframework.ide.vscode.commons.util.text.Region;
import org.springframework.ide.vscode.commons.util.text.TextDocument;

import com.google.common.collect.ImmutableList;

public class SimpleTextDocumentService implements TextDocumentService, DocumentEventListenerManager {

	private static Logger log = LoggerFactory.getLogger(SimpleTextDocumentService.class);
	
	private final SimpleLanguageServer server;
	private final LanguageServerProperties props;
	
	private final ConcurrentMap<String, TrackedDocument> documents = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, TextDocument> documentSnapshots = new ConcurrentHashMap<>();

	private final ListenerList<TextDocumentContentChange> documentChangeListeners = new ListenerList<>();
	private final ListenerList<TextDocument> documentCloseListeners = new ListenerList<>();
	private final ListenerList<TextDocument> documentOpenListeners = new ListenerList<>();
	private List<Consumer<TextDocumentSaveChange>> documentSaveListeners = ImmutableList.of();
	
	private final Executor messageWorkerThreadPool;

	private CompletionHandler completionHandler;
	private CompletionResolveHandler completionResolveHandler;
	private HoverHandler hoverHandler;
	private DefinitionHandler definitionHandler;
	private ReferencesHandler referencesHandler;
	private DocumentSymbolHandler documentSymbolHandler;
	private DocumentHighlightHandler documentHighlightHandler;
	private CodeLensHandler codeLensHandler;
	private CodeLensResolveHandler codeLensResolveHandler;
	private CodeActionHandler codeActionHandler;

	final private ApplicationContext appContext;

	public SimpleTextDocumentService(SimpleLanguageServer server, LanguageServerProperties props, ApplicationContext appContext) {
		this.server = server;
		this.props = props;
		this.appContext = appContext;
		
		this.messageWorkerThreadPool = Executors.newCachedThreadPool();
	}

	/**
	 * Gets all documents this service is tracking, generally these are the documents that have been opened / changed,
	 * and not yet closed.
	 */
	public Collection<TextDocument> getAll() {
		return documentSnapshots.values().stream()
				.collect(Collectors.toList());
	}

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		log.info("change arrived: " + params.getTextDocument().getVersion());

		TextDocumentItem docId = params.getTextDocument();

		String url = docId.getUri();
		LanguageId languageId = LanguageId.of(docId.getLanguageId());
		int version = docId.getVersion();

		if (url != null) {

			String text = params.getTextDocument().getText();
			TrackedDocument td = createDocument(url, languageId, version, text).open();
			
			log.debug("Opened " + td.getOpenCount() + " times: " + url);
			TextDocument doc = td.getDocument();
			
			TextDocument snapshot = doc.copy();
			documentSnapshots.put(url, snapshot);

			documentOpenListeners.fire(snapshot);

			TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent(text);
			TextDocumentContentChange evt = new TextDocumentContentChange(snapshot, ImmutableList.of(change));

			documentChangeListeners.fire(evt);
		}
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		long start = System.currentTimeMillis();

		log.info("change arrived: " + params.getTextDocument().getVersion());

		try {
			VersionedTextDocumentIdentifier docId = params.getTextDocument();
			String url = docId.getUri();

			if (url != null) {
				TextDocument doc = getInternalDocument(url);

				if (doc != null) {
					List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
					doc.apply(params);
					
					TextDocument snapshot = doc.copy();
					documentSnapshots.put(url, snapshot);
					documentChangeListeners.fire(new TextDocumentContentChange(snapshot, changes));
				}
			}
		} catch (BadLocationException e) {
			log.error("", e);
		}
		
		long end = System.currentTimeMillis();
		log.info("change message work done in " + (end - start) + "ms");
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		String url = params.getTextDocument().getUri();

		if (url != null) {

			TrackedDocument doc = documents.get(url);
			if (doc != null) {

				if (doc.close()) {
					documents.remove(url);
					TextDocument lastSnapshot = documentSnapshots.remove(url);

					log.info("Closed: "+url);
					//Clear diagnostics when a file is closed. This makes the errors disapear when the language is changed for
					// a document (this resulst in a dicClose even as being sent to the language server if that changes make the
					// document go 'out of scope'.
					publishDiagnostics(params.getTextDocument(), ImmutableList.of());

					documentCloseListeners.fire(lastSnapshot);
				} else {
					log.warn("Close event ignored! Assuming document still open because openCount = "+doc.getOpenCount());
				}
			} else {
				log.warn("Document closed, but it didn't exist! Close event ignored");
			}
		}
	}

	public void onDidOpen(Consumer<TextDocument> l) {
		documentOpenListeners.add(l);
	}

	public void onDidChangeContent(Consumer<TextDocumentContentChange> l) {
		documentChangeListeners.add(l);
	}

	public void onDidClose(Consumer<TextDocument> l) {
		documentCloseListeners.add(l);
	}

	@Override
	public void onDidSave(Consumer<TextDocumentSaveChange> l) {
		ImmutableList.Builder<Consumer<TextDocumentSaveChange>> builder = ImmutableList.builder();
		builder.addAll(documentSaveListeners);
		builder.add(l);
		documentSaveListeners = builder.build();
	}

	public TextDocument getLatestSnapshot(String url) {
		return documentSnapshots.get(url);
	}

	public TextDocument getLatestSnapshot(TextDocumentPositionParams params) {
		return getLatestSnapshot(params.getTextDocument().getUri());
	}
	
	private TextDocument getInternalDocument(String url) {
		TrackedDocument doc = documents.get(url);
		return doc != null ? doc.getDocument() : null;
	}
	
	private TrackedDocument createDocument(final String url, final LanguageId languageId, final int version, final String text) {
		return documents.computeIfAbsent(url, key -> new TrackedDocument(new TextDocument(url, languageId, version, text)));
	}


	public final static CompletionList NO_COMPLETIONS = new CompletionList(false, Collections.emptyList());
	public final static Hover NO_HOVER = new Hover(ImmutableList.of(), null);
	public final static List<? extends Location> NO_REFERENCES = ImmutableList.of();
	public final static List<? extends SymbolInformation> NO_SYMBOLS = ImmutableList.of();
	public final static List<? extends CodeLens> NO_CODELENS = ImmutableList.of();
	public final static List<DocumentHighlight> NO_HIGHLIGHTS = ImmutableList.of();

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
		log.info("completion request arrived: " + position.getTextDocument().getUri());
		
		return CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
			CompletionHandler h = completionHandler;

			if (h != null) {
				return Either.forRight(completionHandler.handle(cancelToken, position));
			}
			
			log.info("no completions computed due to no completion handler registered for: " + position.getTextDocument().getUri());
			return Either.forRight(NO_COMPLETIONS);
		});
	}

	@Override
	public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
		log.info("Completion item resolve request received: {}", unresolved.getLabel());
		
		return CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
			try {
				CompletionResolveHandler h = completionResolveHandler;
				if (h != null) {
					log.info("Completion item resolve request starting {}", unresolved.getLabel());
					return h.handle(cancelToken, unresolved);
				}
			} catch (CancellationException e) {
				throw e;
			} catch (Exception e) {
				log.warn("exception resolving completion item", e);
			} finally {
				log.info("Completion item resolve request terminated.");
			}
			return null;
		});
	}

	@Override
	public CompletableFuture<Hover> hover(HoverParams hoverParams) {
		log.debug("hover requested for {}", hoverParams.getPosition());
		
		CompletableFuture<Hover> result = CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
			return computeHover(cancelToken, hoverParams);
		});
		
		long timeout = props.getHoverTimeout();
		if (timeout <= 0) {
			return result;
		}
		else {
			return result.completeOnTimeout(NO_HOVER, timeout, TimeUnit.MILLISECONDS);
		}
	}
	
	private Hover computeHover(CancelChecker cancelToken, HoverParams hoverParams) {
		long start = System.currentTimeMillis();

		try {

			log.debug("hover handler starting");
			HoverHandler h = hoverHandler;
			if (h != null) {
				cancelToken.checkCanceled();
				return hoverHandler.handle(cancelToken, hoverParams);
			}
			log.debug("no hover because there is no handler");
			return null;
			
		} finally {
			long end = System.currentTimeMillis();
			log.info("hover computation done in " + (end - start) + "ms");
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			DefinitionParams definitionParams) {

		DefinitionHandler h = this.definitionHandler;
		if (h != null) {
			return CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
				
				cancelToken.checkCanceled();
				
				List<LocationLink> locations = h.handle(cancelToken, definitionParams);
				if (locations == null) {
					// vscode client does not like to receive null result. See: https://github.com/spring-projects/sts4/issues/309
					locations = ImmutableList.of();
				}
				// Workaround for https://github.com/eclipse-theia/theia/issues/6414
				// Theia does not support LocationLink yet
				switch (LspClient.currentClient()) {
					case THEIA:
					case ATOM:
					case INTELLIJ:	
						return Either.forLeft(locations.stream().map(link -> new Location(link.getTargetUri(), link.getTargetRange())).collect(Collectors.toList()));
					default:
						return Either.forRight(locations);
				}
			});
		}
		else {
			return CompletableFuture.completedFuture(Either.forLeft(ImmutableList.of()));
		}
	}

	@Override
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		ReferencesHandler h = this.referencesHandler;
		if (h != null) {

			return CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
				List<? extends Location> list = h.handle(cancelToken, params);
				return list != null && list.isEmpty() ? null : list;
			});
		}
		else {
			return CompletableFuture.completedFuture(ImmutableList.of());
		}
	}

	@Override
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
		DocumentSymbolHandler h = this.documentSymbolHandler;
		if (h != null) {
			
			return CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
				cancelToken.checkCanceled();

				try {
					server.waitForReconcile();
				} catch (Exception e) {
					log.warn("error while waiting for reconcile", e);
				}

				cancelToken.checkCanceled();

				if (server.hasHierarchicalDocumentSymbolSupport() && h instanceof HierarchicalDocumentSymbolHandler) {
					List<? extends DocumentSymbol> r = ((HierarchicalDocumentSymbolHandler)h).handleHierarchic(params);
					//handle it when symbolHandler is sloppy and returns null instead of empty list.
					return r == null
							? ImmutableList.of()
							: r.stream().map(symbolInfo -> Either.<SymbolInformation, DocumentSymbol>forRight(symbolInfo))
										.collect(Collectors.toList());
				} else {
					List<? extends SymbolInformation> r = h.handle(params);
					//handle it when symbolHandler is sloppy and returns null instead of empty list.
					return r == null
							? ImmutableList.of()
							: r.stream().map(symbolInfo -> Either.<SymbolInformation, DocumentSymbol>forLeft(symbolInfo))
										.collect(Collectors.toList());
				}
			});
		}
		else {
			return CompletableFuture.completedFuture(ImmutableList.of());
		}
	}

	@Override
	public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
		// this doesn't happen async, because it accesses the internal documents structure
		// and therefore needs to be executed as part of the main LSP message queue

		TrackedDocument doc = documents.get(params.getTextDocument().getUri());

		if (doc != null) {
			
			return CompletableFutures.computeAsync(cancelToken -> {
				List<Either<Command,CodeAction>> list = doc.getQuickfixes().stream()
						.filter((fix) -> fix.appliesTo(params.getRange(), params.getContext()))
						.map(f -> f.getCodeAction(params.getContext()))
						.map(command -> Either.<Command, CodeAction>forRight(command))
						.collect(Collectors.toList());

				if (codeActionHandler != null) {
					try {
						int start = doc.getDocument().toOffset(params.getRange().getStart());
						int end = doc.getDocument().toOffset(params.getRange().getEnd());
						list.addAll(codeActionHandler.handle(cancelToken, doc.getDocument(), new Region(start, end - start)));
					} catch (Exception e) {
						log.error("Failed to compute quick refactorings", e);
					}
				}
				
				return list;
			});
		} else {
			return CompletableFuture.completedFuture(ImmutableList.of());
		}
	}
	
	@Override
	public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
		CodeLensHandler handler = this.codeLensHandler;

		if (handler != null) {
			return CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
				return handler.handle(cancelToken, params);
			});
		}
		return CompletableFuture.completedFuture(Collections.emptyList());
	}

	@Override
	public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
		CodeLensResolveHandler handler = this.codeLensResolveHandler;
		if (handler != null) {
			
			return CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
				return handler.handle(unresolved);
			});

		}
		else {
			return CompletableFuture.completedFuture(null);
		}
	}

	@Override
	public CompletableFuture<CodeAction> resolveCodeAction(CodeAction ca) {
		return CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
			if (appContext!=null) {
				Map<String, CodeActionResolver> resolvers = appContext.getBeansOfType(CodeActionResolver.class);
				for (CodeActionResolver r : resolvers.values()) {
					r.resolve(ca);
					if (ca.getEdit() != null) {
						return ca;
					}
				}
			}
			return ca;
		});
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		// Workaround for PT 147263283, where error markers in STS are lost on document save.
		// STS 3.9.0 does not use the LSP4E editor for edit manifest.yml, which correctly retains error markers after save.
		// Instead, because the LSP4E editor is missing support for hovers and completions, STS 3.9.0 uses its own manifest editor
		// which extends the YEdit editor. This YEdit editor has a problem, where on save, all error markers are deleted.
		// When STS uses the LSP4E editor and no longer needs its own YEdit-based editor, the issue with error markers disappearing
		// on save should not be a problem anymore, and the workaround below will no longer be needed.
		if (documentSaveListeners != null) {
			CompletableFuture.runAsync(() -> {
				TextDocumentIdentifier docId = params.getTextDocument();
				String url = docId.getUri();
				log.debug("didSave: "+url);
				if (url != null) {
					TextDocument doc = getLatestSnapshot(url);
					if (doc != null) {
						for (Consumer<TextDocumentSaveChange> l : documentSaveListeners) {
							l.accept(new TextDocumentSaveChange(doc));
						}
					}
				}
			}, messageWorkerThreadPool);
		}
	}

	@Override
	public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams highlightParams) {
		DocumentHighlightHandler handler = this.documentHighlightHandler;
		if (handler != null) {
			return CompletableFutures.computeAsync(messageWorkerThreadPool, cancelToken -> {
				return handler.handle(cancelToken, highlightParams);

			});
		}
		else {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
	}

	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams signatureHelpParams) {
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
		return CompletableFuture.completedFuture(Collections.emptyList());
	}

	@Override
	public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
		return CompletableFuture.completedFuture(Collections.emptyList());
	}

	@Override
	public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
		return CompletableFuture.completedFuture(Collections.emptyList());
	}

	@Override
	public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
		return CompletableFuture.completedFuture(null);
	}
	
	//
	//
	//

	public void publishDiagnostics(TextDocumentIdentifier docId, Collection<Diagnostic> diagnostics) {
		LanguageClient client = server.getClient();
		if (client != null && diagnostics != null) {
			PublishDiagnosticsParams params = new PublishDiagnosticsParams();
			params.setUri(docId.getUri());
			params.setDiagnostics(ImmutableList.copyOf(diagnostics));
			client.publishDiagnostics(params);
		}
	}

	public void setQuickfixes(TextDocumentIdentifier docId, List<Quickfix<?>> quickfixes) {
		TrackedDocument td = documents.get(docId.getUri());
		if (td != null) {
			td.setQuickfixes(quickfixes);
		}
	}

	//
	//
	//
	
	public synchronized void onHover(HoverHandler h) {
		Assert.isNull("A hover handler is already set, multiple handlers not supported yet", hoverHandler);
		this.hoverHandler = h;
	}

	public synchronized void onCodeLens(CodeLensHandler h) {
		Assert.isNull("A code lens handler is already set, multiple handlers not supported yet", codeLensHandler);
		this.codeLensHandler = h;
	}
	
	public synchronized void onCodeAction(CodeActionHandler h) {
		Assert.isNull("A code action handler is already set, multiple handlers not supported yet", codeActionHandler);
		this.codeActionHandler = h;
	}

	public boolean hasCodeLensHandler() {
		return this.codeLensHandler != null;
	}

	public synchronized void onCodeLensResolve(CodeLensResolveHandler h) {
		Assert.isNull("A code lens resolve handler is already set, multiple handlers not supported yet", codeLensResolveHandler);
		this.codeLensResolveHandler = h;
	}

	public boolean hasCodeLensResolveProvider() {
		return this.codeLensResolveHandler != null;
	}

	public synchronized void onDocumentSymbol(DocumentSymbolHandler h) {
		Assert.isNull("A DocumentSymbolHandler is already set, multiple handlers not supported yet", documentSymbolHandler);
		this.documentSymbolHandler = h;
	}

	public boolean hasDocumentSymbolHandler() {
		return this.documentSymbolHandler != null;
	}

	public synchronized void onDocumentHighlight(DocumentHighlightHandler h) {
		Assert.isNull("A DocumentHighlightHandler is already set, multiple handlers not supported yet", documentHighlightHandler);
		this.documentHighlightHandler = h;
	}

	public boolean hasDocumentHighlightHandler() {
		return this.documentHighlightHandler != null;
	}

	 public synchronized void onCompletion(CompletionHandler h) {
		Assert.isNull("A completion handler is already set, multiple handlers not supported yet", completionHandler);
		this.completionHandler = h;
	}

	public synchronized void onCompletionResolve(CompletionResolveHandler h) {
		Assert.isNull("A completionResolveHandler handler is already set, multiple handlers not supported yet", completionResolveHandler);
		this.completionResolveHandler = h;
	}

	public synchronized void onDefinition(DefinitionHandler h) {
		Assert.isNull("A defintion handler is already set, multiple handlers not supported yet", definitionHandler);
		this.definitionHandler = h;
	}

	public boolean hasDefinitionHandler() {
		return definitionHandler != null;
	}

	public synchronized void onReferences(ReferencesHandler h) {
		Assert.isNull("A references handler is already set, multiple handlers not supported yet", referencesHandler);
		this.referencesHandler = h;
	}

	public boolean hasReferencesHandler() {
		return this.referencesHandler != null;
	}

}
