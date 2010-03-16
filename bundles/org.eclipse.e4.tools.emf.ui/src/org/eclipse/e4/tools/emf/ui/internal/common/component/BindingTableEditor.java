/*******************************************************************************
 * Copyright (c) 2010 BestSolution.at and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Tom Schindl <tom.schindl@bestsolution.at> - initial API and implementation
 ******************************************************************************/
package org.eclipse.e4.tools.emf.ui.internal.common.component;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import org.eclipse.core.databinding.observable.list.IObservableList;
import org.eclipse.core.databinding.property.list.IListProperty;
import org.eclipse.e4.tools.emf.ui.common.component.AbstractComponentEditor;
import org.eclipse.e4.tools.emf.ui.internal.ObservableColumnLabelProvider;
import org.eclipse.e4.tools.emf.ui.internal.common.ModelEditor;
import org.eclipse.e4.ui.model.application.MApplicationFactory;
import org.eclipse.e4.ui.model.application.MApplicationPackage;
import org.eclipse.e4.ui.model.application.MBindingTable;
import org.eclipse.e4.ui.model.application.MHandler;
import org.eclipse.e4.ui.model.application.MKeyBinding;
import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.databinding.EMFDataBindingContext;
import org.eclipse.emf.databinding.EMFProperties;
import org.eclipse.emf.databinding.FeaturePath;
import org.eclipse.emf.databinding.IEMFListProperty;
import org.eclipse.emf.databinding.edit.EMFEditProperties;
import org.eclipse.emf.databinding.edit.IEMFEditValueProperty;
import org.eclipse.emf.edit.command.AddCommand;
import org.eclipse.emf.edit.command.MoveCommand;
import org.eclipse.emf.edit.command.RemoveCommand;
import org.eclipse.emf.edit.domain.EditingDomain;
import org.eclipse.jface.databinding.swt.IWidgetValueProperty;
import org.eclipse.jface.databinding.swt.WidgetProperties;
import org.eclipse.jface.databinding.viewers.ObservableListContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

public class BindingTableEditor extends AbstractComponentEditor {

	private Composite composite;
	private Image image;
	private EMFDataBindingContext context;
	private ModelEditor editor;
	
	private IListProperty BINDING_TABLE__BINDINGS = EMFProperties.list(MApplicationPackage.Literals.BINDING_TABLE__BINDINGS);


	public BindingTableEditor(EditingDomain editingDomain, ModelEditor editor) {
		super(editingDomain);
		this.editor = editor;
	}

	@Override
	public Image getImage(Object element, Display display) {
		if (image == null) {
			try {
				image = loadSharedImage(display, new URL("platform:/plugin/org.eclipse.e4.ui.model.workbench.edit/icons/full/obj16/BindingTable.gif"));
			} catch (MalformedURLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		return image;
	}

	@Override
	public String getLabel(Object element) {
		return "BindingTable";
	}

	@Override
	public String getDescription(Object element) {
		return "BindingTable bla bla bla";
	}

	@Override
	public Composite getEditor(Composite parent, Object object) {
		if (composite == null) {
			context = new EMFDataBindingContext();
			composite = createForm(parent, context);
		}
		getMaster().setValue(object);
		return composite;
	}

	private Composite createForm(Composite parent, EMFDataBindingContext context) {
		parent = new Composite(parent, SWT.NONE);
		parent.setLayout(new GridLayout(3, false));
		
		IWidgetValueProperty textProp = WidgetProperties.text(SWT.Modify);

		{
			Label l = new Label(parent, SWT.NONE);
			l.setText("Id");

			Text t = new Text(parent, SWT.BORDER);
			GridData gd = new GridData(GridData.FILL_HORIZONTAL);
			gd.horizontalSpan = 2;
			t.setLayoutData(gd);
			context.bindValue(textProp.observeDelayed(200,t), EMFEditProperties.value(getEditingDomain(), MApplicationPackage.Literals.APPLICATION_ELEMENT__ID).observeDetail(getMaster()));
		}
		
		{
			Label l = new Label(parent, SWT.NONE);
			l.setText("Context Id");

			Text t = new Text(parent, SWT.BORDER);
			t.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			context.bindValue(textProp.observeDelayed(200,t), EMFEditProperties.value(getEditingDomain(), MApplicationPackage.Literals.BINDING_TABLE__BINDING_CONTEXT_ID).observeDetail(getMaster()));

			Button b = new Button(parent, SWT.PUSH|SWT.FLAT);
			b.setImage(getImage(t.getDisplay(), SEARCH_IMAGE));
			b.setText("Find ...");			
		}
		
		{
			Label l = new Label(parent, SWT.NONE);
			l.setText("Keybindings");
			l.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING));

			final TableViewer viewer = new TableViewer(parent);
			ObservableListContentProvider cp = new ObservableListContentProvider();
			viewer.setContentProvider(cp);

			GridData gd = new GridData(GridData.FILL_HORIZONTAL);
			gd.heightHint = 300;
			viewer.getControl().setLayoutData(gd);
			viewer.getTable().setHeaderVisible(true);

			{
				IEMFEditValueProperty prop = EMFEditProperties.value(getEditingDomain(), MApplicationPackage.Literals.KEY_SEQUENCE__KEY_SEQUENCE);

				TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
				column.getColumn().setText("KeySequence");
				column.getColumn().setWidth(100);
				column.setLabelProvider(new ObservableColumnLabelProvider<MHandler>(prop.observeDetail(cp.getKnownElements())));
			}

			{
				IEMFEditValueProperty prop = EMFEditProperties.value(getEditingDomain(), FeaturePath.fromList(MApplicationPackage.Literals.KEY_BINDING__COMMAND, MApplicationPackage.Literals.COMMAND__COMMAND_NAME));

				TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
				column.getColumn().setText("Command");
				column.getColumn().setWidth(200);
				column.setLabelProvider(new ObservableColumnLabelProvider<MHandler>(prop.observeDetail(cp.getKnownElements())));
			}

			{
				IEMFEditValueProperty prop = EMFEditProperties.value(getEditingDomain(), MApplicationPackage.Literals.APPLICATION_ELEMENT__ID);

				TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
				column.getColumn().setText("Id");
				column.getColumn().setWidth(170);
				column.setLabelProvider(new ObservableColumnLabelProvider<MHandler>(prop.observeDetail(cp.getKnownElements())));
			}
			
			IEMFListProperty prop = EMFProperties.list(MApplicationPackage.Literals.BINDING_TABLE__BINDINGS);
			viewer.setInput(prop.observeDetail(getMaster()));

			Composite buttonComp = new Composite(parent, SWT.NONE);
			buttonComp.setLayoutData(new GridData(GridData.FILL, GridData.END, false, false));
			GridLayout gl = new GridLayout();
			gl.marginLeft = 0;
			gl.marginRight = 0;
			gl.marginWidth = 0;
			gl.marginHeight = 0;
			buttonComp.setLayout(gl);

			Button b = new Button(buttonComp, SWT.PUSH | SWT.FLAT);
			b.setText("Up");
			b.setImage(getImage(b.getDisplay(), ARROW_UP));
			b.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, true, false));
			b.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					if( ! viewer.getSelection().isEmpty() ) {
						IStructuredSelection s = (IStructuredSelection)viewer.getSelection();
						if( s.size() == 1 ) {
							Object obj = s.getFirstElement();
							MBindingTable container = (MBindingTable) getMaster().getValue();
							int idx = container.getBindings().indexOf(obj) - 1;
							if( idx >= 0 ) {
								Command cmd = MoveCommand.create(getEditingDomain(), getMaster().getValue(), MApplicationPackage.Literals.BINDING_TABLE__BINDINGS, obj, idx);
								
								if( cmd.canExecute() ) {
									getEditingDomain().getCommandStack().execute(cmd);
									viewer.setSelection(new StructuredSelection(obj));
								}
							}
							
						}
					}
				}
			});

			b = new Button(buttonComp, SWT.PUSH | SWT.FLAT);
			b.setText("Down");
			b.setImage(getImage(b.getDisplay(), ARROW_DOWN));
			b.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, true, false));
			b.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					if( ! viewer.getSelection().isEmpty() ) {
						IStructuredSelection s = (IStructuredSelection)viewer.getSelection();
						if( s.size() == 1 ) {
							Object obj = s.getFirstElement();
							MBindingTable container = (MBindingTable) getMaster().getValue();
							int idx = container.getBindings().indexOf(obj) + 1;
							if( idx < container.getBindings().size() ) {
								Command cmd = MoveCommand.create(getEditingDomain(), getMaster().getValue(), MApplicationPackage.Literals.BINDING_TABLE__BINDINGS, obj, idx);
								
								if( cmd.canExecute() ) {
									getEditingDomain().getCommandStack().execute(cmd);
									viewer.setSelection(new StructuredSelection(obj));
								}
							}
							
						}
					}
				}
			});

			b = new Button(buttonComp, SWT.PUSH | SWT.FLAT);
			b.setText("Add ...");
			b.setImage(getImage(b.getDisplay(), TABLE_ADD_IMAGE));
			b.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, true, false));
			b.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					MKeyBinding handler = MApplicationFactory.eINSTANCE.createKeyBinding();
					System.err.println(getMaster().getValue());
					Command cmd = AddCommand.create(getEditingDomain(), getMaster().getValue(), MApplicationPackage.Literals.BINDING_TABLE__BINDINGS, handler);

					if (cmd.canExecute()) {
						getEditingDomain().getCommandStack().execute(cmd);
						editor.setSelection(handler);
					}
				}
			});

			b = new Button(buttonComp, SWT.PUSH | SWT.FLAT);
			b.setText("Remove");
			b.setImage(getImage(b.getDisplay(), TABLE_DELETE_IMAGE));
			b.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, true, false));
			b.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					if( ! viewer.getSelection().isEmpty() ) {
						List<?> keybinding = ((IStructuredSelection)viewer.getSelection()).toList();
						Command cmd = RemoveCommand.create(getEditingDomain(), getMaster().getValue(), MApplicationPackage.Literals.BINDING_TABLE__BINDINGS, keybinding);
						if( cmd.canExecute() ) {
							getEditingDomain().getCommandStack().execute(cmd);
						}
					}
				}
			});
		}
		
		
		return parent;
	}

	@Override
	public IObservableList getChildList(Object element) {
		return BINDING_TABLE__BINDINGS.observe(element);
	}

	@Override
	public String getDetailLabel(Object element) {
		MBindingTable cmd = (MBindingTable) element;
		if (cmd.getBindingContextId() != null && cmd.getBindingContextId().trim().length() > 0) {
			return cmd.getBindingContextId();
		}

		return null;
	}
	
	@Override
	public FeaturePath[] getLabelProperties() {
		return new FeaturePath[] {
			FeaturePath.fromList(MApplicationPackage.Literals.BINDING_TABLE__BINDING_CONTEXT_ID)	
		};
	}
}
