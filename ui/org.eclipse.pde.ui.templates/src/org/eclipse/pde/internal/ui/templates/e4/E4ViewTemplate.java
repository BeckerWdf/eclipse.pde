/*******************************************************************************
 * Copyright (c) 2015 OPCoach
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Olivier Prouvost <olivier.prouvost@opcoach.com> - initial API and implementation (bug #473570)
 *******************************************************************************/

package org.eclipse.pde.internal.ui.templates.e4;

import java.util.ArrayList;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.pde.core.plugin.*;
import org.eclipse.pde.internal.ui.templates.*;
import org.eclipse.pde.ui.IFieldData;
import org.eclipse.pde.ui.templates.PluginReference;

public class E4ViewTemplate extends PDETemplateSection {

	static final String E4_FRAGMENT_FILE = "fragment.e4xmi"; //$NON-NLS-1$

	/**
	 * Constructor for HelloWorldTemplate.
	 */
	public E4ViewTemplate() {
		setPageCount(1);
		createOptions();
	}

	@Override
	public String getSectionId() {
		// return the name of the directory containing the files in org.eclipse.pde.ui.templates/templates_3.5
		return "E4View"; //$NON-NLS-1$
	}

	/*
	 * @see ITemplateSection#getNumberOfWorkUnits()
	 */
	@Override
	public int getNumberOfWorkUnits() {
		return super.getNumberOfWorkUnits() + 1;
	}

	private void createOptions() {
		// first page
		addOption(KEY_PACKAGE_NAME, PDETemplateMessages.E4ViewTemplate_packageName, (String) null, 0);
		addOption("className", PDETemplateMessages.E4ViewTemplate_className, "SampleView", 0); //$NON-NLS-1$ //$NON-NLS-2$
		addOption("viewName", PDETemplateMessages.E4ViewTemplate_name, PDETemplateMessages.E4ViewTemplate_defaultName, 0); //$NON-NLS-1$
		addOption("viewCategoryName", PDETemplateMessages.E4ViewTemplate_categoryName, PDETemplateMessages.E4ViewTemplate_defaultCategoryName, 0); //$NON-NLS-1$

	}

	@Override
	protected void initializeFields(IFieldData data) {
		// In a new project wizard, we don't know this yet - the
		// model has not been created
		initializeFields(data.getId());

	}

	@Override
	public void initializeFields(IPluginModelBase model) {
		// In the new extension wizard, the model exists so
		// we can initialize directly from it
		initializeFields(model.getPluginBase().getId());
	}

	public void initializeFields(String id) {
		initializeOption(KEY_PACKAGE_NAME, getFormattedPackageName(id));
	}

	@Override
	public boolean isDependentOnParentWizard() {
		return true;
	}

	@Override
	public void addPages(Wizard wizard) {
		WizardPage page0 = createPage(0, IHelpContextIds.TEMPLATE_E4_VIEW);
		page0.setTitle(PDETemplateMessages.E4ViewTemplate_title0);
		page0.setDescription(PDETemplateMessages.E4ViewTemplate_desc0);
		wizard.addPage(page0);

		markPagesAdded();
	}



	@Override
	public String getUsedExtensionPoint() {
		return "org.eclipse.e4.workbench.model"; //$NON-NLS-1$
	}

	@Override
	protected void updateModel(IProgressMonitor monitor) throws CoreException {

		createE4ModelExtension();

	}

	private void createE4ModelExtension() throws CoreException {
		IPluginBase plugin = model.getPluginBase();
		IPluginExtension extension = createExtension("org.eclipse.e4.workbench.model", true); //$NON-NLS-1$

		IPluginElement element = model.getFactory().createElement(extension);
		extension.setId(getValue(KEY_PACKAGE_NAME) + ".fragment"); //$NON-NLS-1$

		element.setName("fragment"); //$NON-NLS-1$
		element.setAttribute("apply", "initial"); //$NON-NLS-1$ //$NON-NLS-2$
		element.setAttribute("uri", E4_FRAGMENT_FILE); //$NON-NLS-1$ //$NON-NLS-2$

		extension.add(element);

		if (!extension.isInTheModel())
			plugin.add(extension);
	}


	@Override
	public String[] getNewFiles() {
		return new String[] {"icons/", E4_FRAGMENT_FILE}; //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Override
	public IPluginReference[] getDependencies(String schemaVersion) {


		ArrayList<PluginReference> result = new ArrayList<>();

		//if (schemaVersion != null)
		//	result.add(new PluginReference("org.eclipse.core.runtime", null, 0)); //$NON-NLS-1$
		final int matchRule = IMatchRules.GREATER_OR_EQUAL;

		result.add(new PluginReference("javax.inject", null, matchRule)); //$NON-NLS-1$
		result.add(new PluginReference("org.eclipse.osgi", null, matchRule)); //$NON-NLS-1$
		result.add(new PluginReference("org.eclipse.jface", null, matchRule)); //$NON-NLS-1$
		result.add(new PluginReference("org.eclipse.e4.ui.model.workbench", null, matchRule)); //$NON-NLS-1$
		result.add(new PluginReference("org.eclipse.e4.ui.di", null, matchRule)); //$NON-NLS-1$
		result.add(new PluginReference("org.eclipse.e4.ui.services", null, matchRule)); //$NON-NLS-1$
		result.add(new PluginReference("org.eclipse.e4.core.di.annotations", null, matchRule)); //$NON-NLS-1$

		return result.toArray(new IPluginReference[result.size()]);

	}



}
