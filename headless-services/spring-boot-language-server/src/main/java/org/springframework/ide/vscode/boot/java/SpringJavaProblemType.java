/*******************************************************************************
 * Copyright (c) 2020 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.boot.java;

import org.springframework.ide.vscode.commons.languageserver.reconcile.ProblemSeverity;
import org.springframework.ide.vscode.commons.languageserver.reconcile.ProblemType;
import org.springframework.ide.vscode.commons.util.Assert;

import static org.springframework.ide.vscode.commons.languageserver.reconcile.ProblemSeverity.*;

/**
 * This enum is supposed to represent *all* the different types of problems SpringBoot language server
 * may detect in Java code.
 */
public enum SpringJavaProblemType implements ProblemType {
	
	JAVA_SPEL_EXPRESSION_SYNTAX(ERROR, "SpEL parser raised a ParseException", "SpEL Expression Syntax"),
	
	JAVA_AUTOWIRED_CONSTRUCTOR(WARNING, "Unnecessary @Autowired over the only constructor", "Unnecessary @Autowired");

	private final ProblemSeverity defaultSeverity;
	private String description;
	private String label;

	private SpringJavaProblemType(ProblemSeverity defaultSeverity, String description) {
		this(defaultSeverity, description, null);
	}

	private SpringJavaProblemType(ProblemSeverity defaultSeverity, String description, String label) {
		this.description = description;
		this.defaultSeverity = defaultSeverity;
		this.label = label;
		Assert.isLegal(name().startsWith("JAVA_"));
	}

	@Override
	public ProblemSeverity getDefaultSeverity() {
		return defaultSeverity;
	}

	public String getLabel() {
		if (label==null) {
			label = createDefaultLabel();
		}
		return label;
	}

	@Override
	public String getDescription() {
		return description;
	}

	private String createDefaultLabel() {
		String label = this.toString().substring(5).toLowerCase().replace('_', ' ');
		return Character.toUpperCase(label.charAt(0)) + label.substring(1);
	}

	@Override
	public String getCode() {
		return name();
	}

}
