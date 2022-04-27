/*******************************************************************************
 * Copyright (c) 2016, 2022 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.boot.java;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.ide.vscode.boot.app.BootJavaConfig;
import org.springframework.ide.vscode.boot.app.BootLanguageServerParams;
import org.springframework.ide.vscode.boot.app.SpringSymbolIndex;
import org.springframework.ide.vscode.boot.java.annotations.AnnotationHierarchyAwareLookup;
import org.springframework.ide.vscode.boot.java.autowired.AutowiredHoverProvider;
import org.springframework.ide.vscode.boot.java.conditionals.ConditionalsLiveHoverProvider;
import org.springframework.ide.vscode.boot.java.handlers.BootJavaCodeActionProvider;
import org.springframework.ide.vscode.boot.java.handlers.BootJavaCodeLensEngine;
import org.springframework.ide.vscode.boot.java.handlers.BootJavaDocumentHighlightEngine;
import org.springframework.ide.vscode.boot.java.handlers.BootJavaDocumentSymbolHandler;
import org.springframework.ide.vscode.boot.java.handlers.BootJavaHoverProvider;
import org.springframework.ide.vscode.boot.java.handlers.BootJavaReconcileEngine;
import org.springframework.ide.vscode.boot.java.handlers.BootJavaReferencesHandler;
import org.springframework.ide.vscode.boot.java.handlers.BootJavaWorkspaceSymbolHandler;
import org.springframework.ide.vscode.boot.java.handlers.CodeLensProvider;
import org.springframework.ide.vscode.boot.java.handlers.HighlightProvider;
import org.springframework.ide.vscode.boot.java.handlers.HoverProvider;
import org.springframework.ide.vscode.boot.java.handlers.ReferenceProvider;
import org.springframework.ide.vscode.boot.java.links.SourceLinks;
import org.springframework.ide.vscode.boot.java.livehover.ActiveProfilesProvider;
import org.springframework.ide.vscode.boot.java.livehover.BeanInjectedIntoHoverProvider;
import org.springframework.ide.vscode.boot.java.livehover.ComponentInjectionsHoverProvider;
import org.springframework.ide.vscode.boot.java.livehover.v2.SpringProcessCommandHandler;
import org.springframework.ide.vscode.boot.java.livehover.v2.SpringProcessConnectorLocal;
import org.springframework.ide.vscode.boot.java.livehover.v2.SpringProcessConnectorRemote;
import org.springframework.ide.vscode.boot.java.livehover.v2.SpringProcessConnectorService;
import org.springframework.ide.vscode.boot.java.livehover.v2.SpringProcessLiveDataProvider;
import org.springframework.ide.vscode.boot.java.livehover.v2.SpringProcessLiveHoverUpdater;
import org.springframework.ide.vscode.boot.java.livehover.v2.SpringProcessTracker;
import org.springframework.ide.vscode.boot.java.requestmapping.LiveAppURLSymbolProvider;
import org.springframework.ide.vscode.boot.java.requestmapping.RequestMappingHoverProvider;
import org.springframework.ide.vscode.boot.java.requestmapping.WebfluxHandlerCodeLensProvider;
import org.springframework.ide.vscode.boot.java.requestmapping.WebfluxRouteHighlightProdivder;
import org.springframework.ide.vscode.boot.java.rewrite.ORCompilationUnitCache;
import org.springframework.ide.vscode.boot.java.rewrite.RewriteRecipeRepository;
import org.springframework.ide.vscode.boot.java.rewrite.RewriteRefactorings;
import org.springframework.ide.vscode.boot.java.utils.CompilationUnitCache;
import org.springframework.ide.vscode.boot.java.utils.SpringLiveChangeDetectionWatchdog;
import org.springframework.ide.vscode.boot.java.value.ValueHoverProvider;
import org.springframework.ide.vscode.boot.java.value.ValuePropertyReferencesProvider;
import org.springframework.ide.vscode.boot.metadata.SpringPropertyIndexProvider;
import org.springframework.ide.vscode.commons.languageserver.composable.LanguageServerComponents;
import org.springframework.ide.vscode.commons.languageserver.java.JavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.java.ProjectObserver;
import org.springframework.ide.vscode.commons.languageserver.reconcile.IReconcileEngine;
import org.springframework.ide.vscode.commons.languageserver.util.CodeActionHandler;
import org.springframework.ide.vscode.commons.languageserver.util.CodeLensHandler;
import org.springframework.ide.vscode.commons.languageserver.util.DocumentHighlightHandler;
import org.springframework.ide.vscode.commons.languageserver.util.HoverHandler;
import org.springframework.ide.vscode.commons.languageserver.util.ReferencesHandler;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleTextDocumentService;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleWorkspaceService;
import org.springframework.ide.vscode.commons.util.text.LanguageId;

import com.google.common.collect.ImmutableSet;

/**
 * Language Server for Spring Boot Application Properties files
 *
 * @author Martin Lippert
 */
public class BootJavaLanguageServerComponents implements LanguageServerComponents {

	//TODO: This class is supposed to go away. It is basically a 'collection of beans'.
	// I.e. all the 'components' in here should really become separate beans.

	// So... moving forward...
	// Do not add more components here. You should instead just make your new
	// components into separate beans.

	private static final Logger log = LoggerFactory.getLogger(BootJavaLanguageServerComponents.class);

	public static final Set<LanguageId> LANGUAGES = ImmutableSet.of(LanguageId.JAVA, LanguageId.CLASS);

	private final SimpleLanguageServer server;
	private final BootLanguageServerParams serverParams;

	private final SpringPropertyIndexProvider propertyIndexProvider;

	private final SpringProcessConnectorService liveDataService;

	private final SpringLiveChangeDetectionWatchdog liveChangeDetectionWatchdog;
	private final ProjectObserver projectObserver;
	private final CompilationUnitCache cuCache;

	private JavaProjectFinder projectFinder;
	private BootJavaHoverProvider hoverProvider;
	private CodeLensHandler codeLensHandler;
	private DocumentHighlightHandler highlightsEngine;
	private BootJavaReconcileEngine reconcileEngine;
	private BootJavaCodeActionProvider codeActionProvider;

	private SpringProcessTracker liveProcessTracker;

	public BootJavaLanguageServerComponents(
			ApplicationContext appContext
	) {
		this.server = appContext.getBean(SimpleLanguageServer.class);
		this.serverParams = appContext.getBean(BootLanguageServerParams.class);

		projectFinder = serverParams.projectFinder;
		projectObserver = serverParams.projectObserver;
		this.cuCache = appContext.getBean(CompilationUnitCache.class);

		propertyIndexProvider = serverParams.indexProvider;

		SimpleWorkspaceService workspaceService = server.getWorkspaceService();
		SimpleTextDocumentService documents = server.getTextDocumentService();

		ReferencesHandler referencesHandler = createReferenceHandler(server, projectFinder);
		documents.onReferences(referencesHandler);
		
		//
		// live data component wiring
		//

		// central live data components (to coordinate live data flow)
		liveDataService = appContext.getBean(SpringProcessConnectorService.class);

		// connect the live data provider with the hovers (for data extraction and live updates)
		SpringProcessLiveDataProvider liveDataProvider = appContext.getBean(SpringProcessLiveDataProvider.class);
		SourceLinks sourceLinks = appContext.getBean(SourceLinks.class);
		hoverProvider = createHoverHandler(projectFinder, sourceLinks, liveDataProvider);
		new SpringProcessLiveHoverUpdater(server, hoverProvider, projectFinder, liveDataProvider);

		// deal with locally running processes and their connections
		SpringProcessConnectorLocal liveDataLocalProcessConnector = new SpringProcessConnectorLocal(liveDataService, projectObserver);

		// create and handle commands
		new SpringProcessCommandHandler(server, liveDataService, liveDataLocalProcessConnector, appContext.getBeansOfType(SpringProcessConnectorRemote.class).values());

		// track locally running processes and automatically connect to them if configured to do so
		BootJavaConfig config = appContext.getBean(BootJavaConfig.class);
		liveProcessTracker = new SpringProcessTracker(liveDataLocalProcessConnector, Duration.ofMillis(config.getLiveInformationAutomaticTrackingDelay()));
		
		//
		//
		//

		SpringSymbolIndex indexer = appContext.getBean(SpringSymbolIndex.class);
		documents.onDocumentSymbol(new BootJavaDocumentSymbolHandler(this, indexer));
		workspaceService.onWorkspaceSymbol(new BootJavaWorkspaceSymbolHandler(indexer,
				new LiveAppURLSymbolProvider(liveDataProvider)));


		liveChangeDetectionWatchdog = new SpringLiveChangeDetectionWatchdog(
				this,
				server,
				serverParams.projectObserver,
				projectFinder,
				Duration.ofSeconds(5),
				sourceLinks);

		codeLensHandler = createCodeLensEngine(indexer);
		documents.onCodeLens(codeLensHandler);

		highlightsEngine = createDocumentHighlightEngine(indexer);
		documents.onDocumentHighlight(highlightsEngine);
		
		reconcileEngine = new BootJavaReconcileEngine(server, cuCache, projectFinder);
		
		codeActionProvider = new BootJavaCodeActionProvider(
				server,
				projectFinder,
				cuCache,
				appContext.getBean(RewriteRefactorings.class),
				appContext.getBean(RewriteRecipeRepository.class),
				appContext.getBean(ORCompilationUnitCache.class));
		
		config.addListener(ignore -> {
			log.info("update live process tracker settings - start");
			
			// live information automatic process tracking
			liveProcessTracker.setDelay(config.getLiveInformationAutomaticTrackingDelay());
			liveProcessTracker.setTrackingEnabled(config.isLiveInformationAutomaticTrackingEnabled());

			// live information data fetch params
			liveDataService.setMaxRetryCount(config.getLiveInformationFetchDataMaxRetryCount());
			liveDataService.setRetryDelayInSeconds(config.getLiveInformationFetchDataRetryDelayInSeconds());

			// live change detection watchdog
			if (config.isChangeDetectionEnabled()) {
				liveChangeDetectionWatchdog.enableHighlights();
			}
			else {
				liveChangeDetectionWatchdog.disableHighlights();
			}
			
			reconcileEngine.setSpelExpressionSyntaxValidationEnabled(config.isSpelExpressionValidationEnabled());
			
			log.info("update live process tracker settings - done");
		});
		
		server.doOnInitialized(this::initialized);
		server.onShutdown(this::shutdown);
	}

	public SimpleLanguageServer getServer() {
		return server;
	}

	@Override
	public HoverHandler getHoverProvider() {
		return hoverProvider;
	}

	public CodeLensHandler getCodeLensHandler() {
		return codeLensHandler;
	}

	public DocumentHighlightHandler getDocumentHighlightHandler() {
		return highlightsEngine;
	}
	
	@Override
	public Optional<IReconcileEngine> getReconcileEngine() {
		return Optional.of(reconcileEngine);
	}

	private void initialized() {
		this.liveProcessTracker.start();
		this.liveChangeDetectionWatchdog.start();
	}

	private void shutdown() {
		this.liveProcessTracker.stop();
		this.liveChangeDetectionWatchdog.shutdown();
		this.cuCache.dispose();
	}


	protected BootJavaHoverProvider createHoverHandler(JavaProjectFinder javaProjectFinder, SourceLinks sourceLinks,
			SpringProcessLiveDataProvider liveDataProvider) {

		AnnotationHierarchyAwareLookup<HoverProvider> providers = new AnnotationHierarchyAwareLookup<>();

		ValueHoverProvider valueHoverProvider = new ValueHoverProvider();
		RequestMappingHoverProvider requestMappingHoverProvider = new RequestMappingHoverProvider();
		AutowiredHoverProvider autowiredHoverProvider = new AutowiredHoverProvider(sourceLinks);
		ComponentInjectionsHoverProvider componentInjectionsHoverProvider = new ComponentInjectionsHoverProvider(sourceLinks);
		BeanInjectedIntoHoverProvider beanInjectedIntoHoverProvider = new BeanInjectedIntoHoverProvider(sourceLinks);
		ConditionalsLiveHoverProvider conditionalsLiveHoverProvider = new ConditionalsLiveHoverProvider();

		providers.put(org.springframework.ide.vscode.boot.java.value.Constants.SPRING_VALUE, valueHoverProvider);

		providers.put(Annotations.SPRING_REQUEST_MAPPING, requestMappingHoverProvider);
		providers.put(Annotations.SPRING_GET_MAPPING, requestMappingHoverProvider);
		providers.put(Annotations.SPRING_POST_MAPPING, requestMappingHoverProvider);
		providers.put(Annotations.SPRING_PUT_MAPPING, requestMappingHoverProvider);
		providers.put(Annotations.SPRING_DELETE_MAPPING, requestMappingHoverProvider);
		providers.put(Annotations.SPRING_PATCH_MAPPING, requestMappingHoverProvider);
		providers.put(Annotations.PROFILE, new ActiveProfilesProvider());

		providers.put(Annotations.AUTOWIRED, autowiredHoverProvider);
		providers.put(Annotations.INJECT, autowiredHoverProvider);
		providers.put(Annotations.COMPONENT, componentInjectionsHoverProvider);
		providers.put(Annotations.BEAN, beanInjectedIntoHoverProvider);

		providers.put(Annotations.CONDITIONAL, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_BEAN, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_MISSING_BEAN, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_PROPERTY, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_RESOURCE, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_CLASS, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_MISSING_CLASS, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_CLOUD_PLATFORM, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_WEB_APPLICATION, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_NOT_WEB_APPLICATION, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_ENABLED_INFO_CONTRIBUTOR, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_ENABLED_RESOURCE_CHAIN, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_ENABLED_ENDPOINT, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_ENABLED_HEALTH_INDICATOR, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_EXPRESSION, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_JAVA, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_JNDI, conditionalsLiveHoverProvider);
		providers.put(Annotations.CONDITIONAL_ON_SINGLE_CANDIDATE, conditionalsLiveHoverProvider);

		return new BootJavaHoverProvider(this, javaProjectFinder, providers, liveDataProvider);
	}

	protected ReferencesHandler createReferenceHandler(SimpleLanguageServer server, JavaProjectFinder projectFinder) {
		Map<String, ReferenceProvider> providers = new HashMap<>();
		providers.put(org.springframework.ide.vscode.boot.java.value.Constants.SPRING_VALUE,
				new ValuePropertyReferencesProvider(server));

		return new BootJavaReferencesHandler(this, projectFinder, providers);
	}

	protected BootJavaCodeLensEngine createCodeLensEngine(SpringSymbolIndex index) {
		Collection<CodeLensProvider> codeLensProvider = new ArrayList<>();
		codeLensProvider.add(new WebfluxHandlerCodeLensProvider(index));

		return new BootJavaCodeLensEngine(this, codeLensProvider);
	}

	protected BootJavaDocumentHighlightEngine createDocumentHighlightEngine(SpringSymbolIndex indexer) {
		Collection<HighlightProvider> highlightProvider = new ArrayList<>();
		highlightProvider.add(new WebfluxRouteHighlightProdivder(indexer));

		return new BootJavaDocumentHighlightEngine(this, highlightProvider);
	}

	public ProjectObserver getProjectObserver() {
		return projectObserver;
	}

	public JavaProjectFinder getProjectFinder() {
		return projectFinder;
	}

	public SpringPropertyIndexProvider getSpringPropertyIndexProvider() {
		return propertyIndexProvider;
	}

	public CompilationUnitCache getCompilationUnitCache() {
		return cuCache;
	}

	public SimpleTextDocumentService getTextDocumentService() {
		return server.getTextDocumentService();
	}

	public BootLanguageServerParams getServerParams() {
		return this.serverParams;
	}

	@Override
	public Set<LanguageId> getInterestingLanguages() {
		return LANGUAGES;
	}

	@Override
	public Optional<CodeActionHandler> getCodeActionProvider() {
		return Optional.ofNullable(codeActionProvider);
	}

	
}
