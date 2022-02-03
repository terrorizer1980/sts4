package org.springframework.ide.vscode.boot.java.rewrite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.shaded.jgit.diff.Edit;
import org.openrewrite.shaded.jgit.diff.EditList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ide.vscode.boot.java.utils.JGitUtils;
import org.springframework.ide.vscode.commons.languageserver.completion.DocumentEdits;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleTextDocumentService;
import org.springframework.ide.vscode.commons.util.BadLocationException;
import org.springframework.ide.vscode.commons.util.text.IDocument;
import org.springframework.ide.vscode.commons.util.text.IRegion;
import org.springframework.ide.vscode.commons.util.text.LanguageId;
import org.springframework.ide.vscode.commons.util.text.TextDocument;

public class ORDocUtils {
		
	private static final Logger log = LoggerFactory.getLogger(ORDocUtils.class);
	
	public static Optional<DocumentEdits> computeEdits(IDocument doc, Result result) {
			TextDocument newDoc = new TextDocument(null, LanguageId.PLAINTEXT, 0, result.getAfter().printAll());

			EditList diff = JGitUtils.getDiff(result.getBefore().printAll(), newDoc.get());
			if (!diff.isEmpty()) {
				DocumentEdits edits = new DocumentEdits(doc, false);
				for (Edit e : diff) {
					try {
						switch(e.getType()) {
						case DELETE:
							edits.delete(doc.getLineOffset(e.getBeginA()), getStartOfLine(doc, e.getEndA()));
							break;
						case INSERT:
							edits.insert(doc.getLineOffset(e.getBeginA()), newDoc.textBetween(newDoc.getLineOffset(e.getBeginB()), getStartOfLine(newDoc, e.getEndB())));
							break;
						case REPLACE:
							edits.replace(doc.getLineOfOffset(e.getBeginA()), getStartOfLine(doc, e.getEndA()), newDoc.textBetween(newDoc.getLineOffset(e.getBeginB()), getStartOfLine(newDoc, e.getEndB())));
							break;
						case EMPTY:
							break;
						}
					} catch (BadLocationException ex) {
						log.error("Diff conversion failed", ex);
					}
				}
				return Optional.of(edits);
			}
		return Optional.empty();	

	}
	
	public static Optional<TextDocumentEdit> computeTextDocEdit(TextDocument doc, Result result) {
		TextDocument newDoc = new TextDocument(null, LanguageId.PLAINTEXT, 0, result.getAfter().printAll());

		EditList diff = JGitUtils.getDiff(result.getBefore().printAll(), newDoc.get());
		if (!diff.isEmpty()) {
			TextDocumentEdit edit = new TextDocumentEdit();
			edit.setTextDocument(new VersionedTextDocumentIdentifier(doc.getUri(), doc.getVersion()));
			List<TextEdit> textEdits = new ArrayList<>();
			edit.setEdits(textEdits);
			for (Edit e : diff) {
				try {
					switch(e.getType()) {
					case DELETE:
						TextEdit textEdit = new TextEdit();
						int start = doc.getLineOffset(e.getBeginA());
						int end = getStartOfLine(doc, e.getEndA());
						textEdit.setRange(new Range(doc.toPosition(start), doc.toPosition(end)));
						textEdit.setNewText("");
						textEdits.add(textEdit);
						break;
					case INSERT:
						textEdit = new TextEdit();
						Position position = doc.toPosition(doc.getLineOffset(e.getBeginA()));
						textEdit.setRange(new Range(position, position));
						textEdit.setNewText(newDoc.textBetween(newDoc.getLineOffset(e.getBeginB()), getStartOfLine(newDoc, e.getEndB())));
						textEdits.add(textEdit);
						break;
					case REPLACE:
						textEdit = new TextEdit();
						start = doc.getLineOffset(e.getBeginA());
						end = getStartOfLine(doc, e.getEndA());
						textEdit.setRange(new Range(doc.toPosition(start), doc.toPosition(end)));
						textEdit.setNewText(newDoc.textBetween(newDoc.getLineOffset(e.getBeginB()), getStartOfLine(newDoc, e.getEndB())));
						textEdits.add(textEdit);
						break;
					case EMPTY:
						break;
					}
				} catch (BadLocationException ex) {
					log.error("Diff conversion failed", ex);
				}
			}
			return Optional.of(edit);
		}
		return Optional.empty();
	}
	
	public static Optional<TextDocumentEdit> computeSimpleTextDocEdit(TextDocument doc, Result result) {
		TextDocument newDoc = new TextDocument(null, LanguageId.PLAINTEXT, 0, result.getAfter().printAll());

		EditList diff = JGitUtils.getDiff(result.getBefore().printAll(), newDoc.get());
		if (!diff.isEmpty()) {
			TextDocumentEdit edit = new TextDocumentEdit();
			edit.setTextDocument(new VersionedTextDocumentIdentifier(doc.getUri(), doc.getVersion()));
			TextEdit te = new TextEdit();
			te.setNewText(result.getAfter().printAll());
			try {
				te.setRange(new Range(new Position(0,0), doc.toPosition(doc.getLength())));
			} catch (BadLocationException e) {
				// ignore
			}
			edit.setEdits(List.of(te));
			return Optional.of(edit);
		}
		return Optional.empty();		
	}
	
	private static void writeFile(SourceFile source) {
		try {
			Files.writeString(source.getSourcePath(), source.printAll(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			log.error("Failed to save changes to " + source.getSourcePath(), e);
		}
	}
	
	private static void createNewFile(SourceFile source) {
		try {
			Path parentFolder = source.getSourcePath().getParent();
			if (parentFolder != null) {
				Files.createDirectories(parentFolder);
			}
			Files.writeString(source.getSourcePath(), source.printAll(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		} catch (IOException e) {
			log.error("Failed to create " + source.getSourcePath(), e);
		}
	}
	
	private static void deleteFile(SourceFile source) {
		try {
			Files.deleteIfExists(source.getSourcePath());
		} catch (IOException e) {
			log.error("Failed to delete " + source.getSourcePath(), e);
		}
	}

	private static int getStartOfLine(IDocument doc, int lineNumber) {
		IRegion lineInformation = doc.getLineInformation(lineNumber);
		if (lineInformation != null) {
			return lineInformation.getOffset();
		}
		if (lineNumber > 0) {
			IRegion currentLine = doc.getLineInformation(lineNumber - 1);
			return currentLine.getOffset() + currentLine.getLength();
		}
		return 0;
	}
	
	public static Optional<WorkspaceEdit> createWorkspaceEdit(Path absoluteProjectDir, SimpleTextDocumentService documents, List<Result> results) {
		if (results.isEmpty()) {
			return Optional.empty();
		}
		WorkspaceEdit we = new WorkspaceEdit();
		we.setDocumentChanges(new ArrayList<>());
		for (Result result : results) {
			if (result.getBefore() == null) {
				String docUri = absoluteProjectDir.resolve(result.getAfter().getSourcePath()).toUri().toString();
				CreateFile ro = new CreateFile();
				ro.setUri(docUri);
				we.getDocumentChanges().add(Either.forRight(ro));
				
				TextDocumentEdit te = new TextDocumentEdit();
				te.setTextDocument(new VersionedTextDocumentIdentifier(docUri, 0));
				Position cursor = new Position(0,0);
				te.setEdits(List.of(new TextEdit(new Range(cursor, cursor), result.getAfter().printAll())));
				we.getDocumentChanges().add(Either.forLeft(te));
			} else if (result.getAfter() == null) {
				String docUri = absoluteProjectDir.resolve(result.getBefore().getSourcePath()).toUri().toString();
				we.getDocumentChanges().add(Either.forRight(new DeleteFile(docUri)));
			} else {
				String docUri = absoluteProjectDir.resolve(result.getBefore().getSourcePath()).toUri().toString();
				TextDocument doc = documents.getLatestSnapshot(docUri);
				if (doc == null) {
					doc = new TextDocument(docUri, null, 0, result.getBefore().printAll());
//					ORDocUtils.computeSimpleTextDocEdit(doc, result).ifPresent(te -> we.getDocumentChanges().add(Either.forLeft(te)));
					ORDocUtils.computeTextDocEdit(doc, result).ifPresent(te -> we.getDocumentChanges().add(Either.forLeft(te)));
				} else {
//					ORDocUtils.computeSimpleTextDocEdit(doc, result).ifPresent(te -> we.getDocumentChanges().add(Either.forLeft(te)));
					ORDocUtils.computeTextDocEdit(doc, result).ifPresent(te -> we.getDocumentChanges().add(Either.forLeft(te)));
				}
			}
			
		}
		return Optional.of(we);
	}
	
	public static CompletableFuture<Void> applyFileChangesForNonOpenedDocs(SimpleTextDocumentService documents, List<Result> results) {
		List<CompletableFuture<Void>> futures = new ArrayList<>(results.size());
		for (Result result : results) {
			if (result.getBefore() == null) {				
				// create the new file physically.
				futures.add(CompletableFuture.runAsync(() -> createNewFile(result.getAfter())));				
			} else if (result.getAfter() == null) {
				// physically delete file
				futures.add(CompletableFuture.runAsync(() -> deleteFile(result.getBefore())));
			} else {
				String docUri = result.getBefore().getSourcePath().toUri().toString();
				TextDocument doc = documents.getLatestSnapshot(docUri);
				if (doc == null) {
					// save file directly if no doc found
					futures.add(CompletableFuture.runAsync(() -> writeFile(result.getAfter())));
				}
			}
		}
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
	}
	
}
