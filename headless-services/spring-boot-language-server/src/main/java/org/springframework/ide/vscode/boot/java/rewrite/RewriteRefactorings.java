package org.springframework.ide.vscode.boot.java.rewrite;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.springframework.context.ApplicationContext;
import org.springframework.ide.vscode.commons.languageserver.util.CodeActionResolver;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class RewriteRefactorings implements CodeActionResolver {
	
	private Map<String, BiFunction<ApplicationContext, List<?>, WorkspaceEdit>> refactoringsMap = new ConcurrentHashMap<>();
	
	private ApplicationContext appContext;
	
	public RewriteRefactorings(ApplicationContext appContext) {
		this.appContext = appContext;
	}
	
	public void addRefactoring(String id, BiFunction<ApplicationContext, List<?>, WorkspaceEdit> handler) {
		if (refactoringsMap.containsKey(id)) {
			throw new IllegalStateException("Refactoring with id '" + id + "' already exists!");
		}
		refactoringsMap.put(id, handler);
	}
	
	@Override
	public void resolve(CodeAction codeAction) {
		if (codeAction.getData() instanceof JsonObject) {
			JsonObject o = (JsonObject) codeAction.getData();
			try {
				Data data = new Gson().fromJson(o, Data.class);
				if (data != null && data.id != null) {
					BiFunction<ApplicationContext, List<?>, WorkspaceEdit> handler = refactoringsMap.get(data.id);
					if (handler != null) {
						WorkspaceEdit edit = handler.apply(appContext, data.arguments);
						if (edit != null) {
							codeAction.setEdit(edit);
						}
					}
				}
			} catch (Exception e) {
				// ignore
			}
		}
	}

	public static class Data {
		public String id;
		public List<?> arguments;
		public Data(String id, List<?> arguments) {
			this.id = id;
			this.arguments = arguments;
		}
	}

}
