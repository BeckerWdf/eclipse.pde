/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: IBM - Initial API and implementation
 ******************************************************************************/

package org.eclipse.pde.internal.build.builder;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.jar.Attributes.Name;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.build.Constants;
import org.eclipse.pde.internal.build.*;
import org.eclipse.pde.internal.build.AbstractScriptGenerator.MissingProperties;
import org.eclipse.pde.internal.build.site.*;
import org.eclipse.pde.internal.build.site.compatibility.*;
import org.osgi.framework.Version;

public class SourceGenerator implements IPDEBuildConstants, IBuildPropertiesConstants {
	private static final String COMMENT_START_TAG = "<!--"; //$NON-NLS-1$
	private static final String COMMENT_END_TAG = "-->"; //$NON-NLS-1$
	private static final String PLUGIN_START_TAG = "<plugin"; //$NON-NLS-1$
	private static final String FEATURE_START_TAG = "<feature";//$NON-NLS-1$
	private static final String FRAGMENT_START_TAG = "<fragment"; //$NON-NLS-1$
	private static final String VERSION = "version";//$NON-NLS-1$
	private static final String PLUGIN_VERSION = "plugin-version"; //$NON-NLS-1$
	private static final String TEMPLATE = "data"; //$NON-NLS-1$

	private String featureRootLocation;
	private String sourceFeatureId;
	private Properties buildProperties;

	private BuildDirector director;
	private String[] extraEntries;
	private Map excludedEntries;

	public void setSourceFeatureId(String id) {
		sourceFeatureId = id;
	}

	public void setExtraEntries(String[] extraEntries) {
		this.extraEntries = extraEntries;
	}

	public void setDirector(BuildDirector director) {
		this.director = director;
	}

	private void initialize(BuildTimeFeature feature, String sourceFeatureName) throws CoreException {
		featureRootLocation = feature.getRootLocation();
		setSourceFeatureId(sourceFeatureName);
		collectSourceEntries(feature);
	}

	private BuildTimeSite getSite() throws CoreException {
		return director.getSite(false);
	}

	private String getWorkingDirectory() {
		return AbstractScriptGenerator.getWorkingDirectory();
	}

	private Properties getBuildProperties() throws CoreException {
		if (buildProperties == null)
			buildProperties = AbstractScriptGenerator.readProperties(featureRootLocation, PROPERTIES_FILE, IStatus.OK);
		return buildProperties;
	}

	protected Properties getBuildProperties(BundleDescription model) throws CoreException {
		return AbstractScriptGenerator.readProperties(model.getLocation(), PROPERTIES_FILE, IStatus.OK);
	}

	private String getSourcePluginName(FeatureEntry plugin, boolean versionSuffix) {
		return plugin.getId() + (versionSuffix ? "_" + plugin.getVersion() : ""); //$NON-NLS-1$	//$NON-NLS-2$
	}

	private void collectSourceEntries(BuildTimeFeature feature) throws CoreException {
		FeatureEntry[] pluginList = feature.getPluginEntries();
		for (int i = 0; i < pluginList.length; i++) {
			FeatureEntry entry = pluginList[i];
			BundleDescription model;
			if (director.selectConfigs(entry).size() == 0)
				continue;

			String versionRequested = entry.getVersion();
			model = getSite().getRegistry().getResolvedBundle(entry.getId(), versionRequested);
			if (model == null)
				continue;

			collectSourcePlugins(feature, pluginList[i], model);
		}
	}

	private void collectSourcePlugins(BuildTimeFeature feature, FeatureEntry pluginEntry, BundleDescription model) throws CoreException {
		//Do not collect plug-ins for which we are not generating build.xml
		try {
			if (AbstractScriptGenerator.readProperties(model.getLocation(), PROPERTIES_FILE, IStatus.OK) == MissingProperties.getInstance())
				return;
		} catch (CoreException e) {
			return;
		}
		//don't gather if we are doing individual source bundles
		if (AbstractScriptGenerator.getPropertyAsBoolean("individualSourceBundles")) //$NON-NLS-1$
			return;

		// The generic entry may not be part of the configuration we are building however,
		// the code for a non platform specific plugin still needs to go into a generic source plugin
		String sourceId = computeSourceFeatureName(feature, false);
		if (pluginEntry.getOS() == null && pluginEntry.getWS() == null && pluginEntry.getArch() == null) {
			director.sourceToGather.addElementEntry(sourceId, model);
			return;
		}
		// Here we fan the plugins into the source fragment where they should go
		List correctConfigs = director.selectConfigs(pluginEntry);
		for (Iterator iter = correctConfigs.iterator(); iter.hasNext();) {
			Config configInfo = (Config) iter.next();
			director.sourceToGather.addElementEntry(sourceId + "." + configInfo.toString("."), model); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * Method generateSourceFeature.
	 * @throws Exception 
	 */
	public BuildTimeFeature generateSourceFeature(BuildTimeFeature feature, String sourceFeatureName) throws CoreException {
		initialize(feature, sourceFeatureName);
		BuildTimeFeature sourceFeature = createSourceFeature(feature);

		associateExtraEntries(sourceFeature);

		FeatureEntry sourcePlugin;
		if (AbstractScriptGenerator.getPropertyAsBoolean("individualSourceBundles")) { //$NON-NLS-1$
			/* individual source bundles */
			FeatureEntry[] plugins = feature.getPluginEntries();
			for (int i = 0; i < plugins.length; i++) {
				if (director.selectConfigs(plugins[i]).size() == 0)
					continue;
				createSourceBundle(sourceFeature, plugins[i]);
			}
		} else {
			/* one source bundle + platform fragments */
			if (AbstractScriptGenerator.isBuildingOSGi())
				sourcePlugin = create30SourcePlugin(sourceFeature);
			else
				sourcePlugin = createSourcePlugin(sourceFeature);

			generateSourceFragments(sourceFeature, sourcePlugin);
		}

		writeSourceFeature(sourceFeature);

		return sourceFeature;
	}

	// Add extra plugins into the given feature.
	private void associateExtraEntries(BuildTimeFeature sourceFeature) throws CoreException {
		BundleDescription model;
		FeatureEntry entry;

		for (int i = 1; i < extraEntries.length; i++) {
			// see if we have a plug-in or a fragment
			if (extraEntries[i].startsWith("feature@")) { //$NON-NLS-1$
				String id = extraEntries[i].substring(8);
				entry = new FeatureEntry(id, GENERIC_VERSION_NUMBER, false);
				sourceFeature.addEntry(entry);
			} else if (extraEntries[i].startsWith("plugin@")) { //$NON-NLS-1$
				Object[] items = Utils.parseExtraBundlesString(extraEntries[i], true);
				model = getSite().getRegistry().getResolvedBundle((String) items[0], ((Version) items[1]).toString());
				if (model == null) {
					IStatus status = getSite().missingPlugin((String) items[0], ((Version) items[1]).toString(), false);
					BundleHelper.getDefault().getLog().log(status);
					continue;
				}
				entry = new FeatureEntry(model.getSymbolicName(), model.getVersion().toString(), true);
				entry.setUnpack(((Boolean) items[2]).booleanValue());
				sourceFeature.addEntry(entry);
			} else if (extraEntries[i].startsWith("exclude@")) { //$NON-NLS-1$
				if (excludedEntries == null)
					excludedEntries = new HashMap();
				Object[] items = Utils.parseExtraBundlesString(extraEntries[i], true);
				if (excludedEntries.containsKey(items[0])) {
					((List) excludedEntries.get(items[0])).add(items[1]);
				}
				List versionList = new ArrayList();
				versionList.add(items[1]);
				excludedEntries.put(items[0], versionList);
			}
		}
	}

	private void generateSourceFragments(BuildTimeFeature sourceFeature, FeatureEntry sourcePlugin) throws CoreException {
		Map fragments = director.sourceToGather.getElementEntries();
		for (Iterator iter = AbstractScriptGenerator.getConfigInfos().iterator(); iter.hasNext();) {
			Config configInfo = (Config) iter.next();
			if (configInfo.equals(Config.genericConfig()))
				continue;
			String sourceFragmentId = sourceFeature.getId() + "." + configInfo.toString("."); //$NON-NLS-1$ //$NON-NLS-2$
			Set fragmentEntries = (Set) fragments.get(sourceFragmentId);
			if (fragmentEntries == null || fragmentEntries.size() == 0)
				continue;
			FeatureEntry sourceFragment = new FeatureEntry(sourceFragmentId, sourceFeature.getVersion(), true);
			sourceFragment.setEnvironment(configInfo.getOs(), configInfo.getWs(), configInfo.getArch(), null);
			sourceFragment.setFragment(true);
			//sourceFeature.addPluginEntryModel(sourceFragment);
			if (AbstractScriptGenerator.isBuildingOSGi())
				create30SourceFragment(sourceFragment, sourcePlugin);
			else
				createSourceFragment(sourceFragment, sourcePlugin);

			sourceFeature.addEntry(sourceFragment);
		}
	}

	private String computeSourceFeatureName(Feature featureForName, boolean withNumber) throws CoreException {
		String sourceFeatureName = getBuildProperties().getProperty(PROPERTY_SOURCE_FEATURE_NAME);
		if (sourceFeatureName == null)
			sourceFeatureName = sourceFeatureId;
		if (sourceFeatureName == null)
			sourceFeatureName = featureForName.getId() + ".source"; //$NON-NLS-1$
		return sourceFeatureName + (withNumber ? "_" + featureForName.getVersion() : ""); //$NON-NLS-1$ //$NON-NLS-2$
	}

	// Create a feature object representing a source feature based on the featureExample
	private BuildTimeFeature createSourceFeature(Feature featureExample) throws CoreException {
		String id = computeSourceFeatureName(featureExample, false);
		String version = featureExample.getVersion();
		BuildTimeFeature result = new BuildTimeFeature(id, version);

		result.setLabel(featureExample.getLabel());
		result.setProviderName(featureExample.getProviderName());
		result.setImage(featureExample.getImage());
		result.setInstallHandler(featureExample.getInstallHandler());
		result.setInstallHandlerLibrary(featureExample.getInstallHandlerLibrary());
		result.setInstallHandlerURL(featureExample.getInstallHandlerURL());
		result.setDescription(featureExample.getDescription());
		result.setDescriptionURL(featureExample.getDescriptionURL());
		result.setCopyright(featureExample.getCopyright());
		result.setCopyrightURL(featureExample.getCopyrightURL());
		result.setLicense(featureExample.getLicense());
		result.setLicenseURL(featureExample.getLicenseURL());
		result.setUpdateSiteLabel(featureExample.getUpdateSiteLabel());
		result.setUpdateSiteURL(featureExample.getUpdateSiteURL());

		URLEntry[] siteEntries = featureExample.getDiscoverySites();
		for (int i = 0; i < siteEntries.length; i++) {
			result.addDiscoverySite(siteEntries[i].getAnnotation(), siteEntries[i].getURL());
		}

		result.setEnvironment(featureExample.getOS(), featureExample.getWS(), featureExample.getOS(), null);

		int contextLength = featureExample instanceof BuildTimeFeature ? ((BuildTimeFeature) featureExample).getContextQualifierLength() : -1;
		result.setContextQualifierLength(contextLength);
		return result;
	}

	/**
	 * Method createSourcePlugin.
	 */
	private FeatureEntry createSourcePlugin(BuildTimeFeature sourceFeature) throws CoreException {
		//Create an object representing the plugin
		FeatureEntry result = new FeatureEntry(sourceFeature.getId(), sourceFeature.getVersion(), true);
		sourceFeature.addEntry(result);
		// create the directory for the plugin
		IPath sourcePluginDirURL = new Path(getWorkingDirectory() + '/' + DEFAULT_PLUGIN_LOCATION + '/' + getSourcePluginName(result, false));
		File sourcePluginDir = sourcePluginDirURL.toFile();
		sourcePluginDir.mkdirs();

		// Create the plugin.xml
		StringBuffer buffer;
		Path templatePluginXML = new Path(TEMPLATE + "/21/plugin/" + Constants.PLUGIN_FILENAME_DESCRIPTOR); //$NON-NLS-1$
		URL templatePluginURL = BundleHelper.getDefault().find(templatePluginXML);
		if (templatePluginURL == null) {
			IStatus status = new Status(IStatus.WARNING, PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_READING_FILE, NLS.bind(Messages.error_readingDirectory, templatePluginXML), null);
			BundleHelper.getDefault().getLog().log(status);
			return null;
		}
		try {
			buffer = Utils.readFile(templatePluginURL.openStream());
		} catch (IOException e1) {
			String message = NLS.bind(Messages.exception_readingFile, templatePluginURL.toExternalForm());
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_READING_FILE, message, e1));
		}
		int beginId = Utils.scan(buffer, 0, REPLACED_PLUGIN_ID);
		buffer.replace(beginId, beginId + REPLACED_PLUGIN_ID.length(), result.getId());
		//set the version number
		beginId = Utils.scan(buffer, beginId, REPLACED_PLUGIN_VERSION);
		buffer.replace(beginId, beginId + REPLACED_PLUGIN_VERSION.length(), result.getVersion());
		try {
			Utils.transferStreams(new ByteArrayInputStream(buffer.toString().getBytes()), new FileOutputStream(sourcePluginDirURL.append(Constants.PLUGIN_FILENAME_DESCRIPTOR).toOSString()));
		} catch (IOException e1) {
			String message = NLS.bind(Messages.exception_writingFile, templatePluginURL.toExternalForm());
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_READING_FILE, message, e1));
		}
		Collection copiedFiles = Utils.copyFiles(featureRootLocation + '/' + "sourceTemplatePlugin", sourcePluginDir.getAbsolutePath()); //$NON-NLS-1$
		if (copiedFiles.contains(Constants.PLUGIN_FILENAME_DESCRIPTOR)) {
			replaceXMLAttribute(sourcePluginDirURL.append(Constants.PLUGIN_FILENAME_DESCRIPTOR).toOSString(), PLUGIN_START_TAG, VERSION, result.getVersion());
		}
		//	If a build.properties file already exist then we use it supposing it is correct.
		File buildProperty = sourcePluginDirURL.append(PROPERTIES_FILE).toFile();
		if (!buildProperty.exists()) {
			copiedFiles.add(Constants.PLUGIN_FILENAME_DESCRIPTOR); //Because the plugin.xml is not copied, we need to add it to the file
			copiedFiles.add("src/**/*.zip"); //$NON-NLS-1$
			Properties sourceBuildProperties = new Properties();
			sourceBuildProperties.put(PROPERTY_BIN_INCLUDES, Utils.getStringFromCollection(copiedFiles, ",")); //$NON-NLS-1$
			sourceBuildProperties.put(SOURCE_PLUGIN_ATTRIBUTE, "true"); //$NON-NLS-1$
			try {
				OutputStream buildFile = new BufferedOutputStream(new FileOutputStream(buildProperty));
				try {
					sourceBuildProperties.store(buildFile, null);
				} finally {
					buildFile.close();
				}
			} catch (FileNotFoundException e) {
				String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
			} catch (IOException e) {
				String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
			}
		}
		PDEState state = getSite().getRegistry();
		BundleDescription oldBundle = state.getResolvedBundle(result.getId());
		if (oldBundle != null)
			state.getState().removeBundle(oldBundle);
		state.addBundle(sourcePluginDir);
		return result;
	}

	private void create30SourceFragment(FeatureEntry fragment, FeatureEntry plugin) throws CoreException {
		// create the directory for the plugin
		Path sourceFragmentDirURL = new Path(getWorkingDirectory() + '/' + DEFAULT_PLUGIN_LOCATION + '/' + getSourcePluginName(fragment, false));
		File sourceFragmentDir = new File(sourceFragmentDirURL.toOSString());
		new File(sourceFragmentDir, "META-INF").mkdirs(); //$NON-NLS-1$
		try {
			// read the content of the template file
			Path fragmentPath = new Path(TEMPLATE + "/30/fragment/" + Constants.BUNDLE_FILENAME_DESCRIPTOR);//$NON-NLS-1$
			URL templateLocation = BundleHelper.getDefault().find(fragmentPath);
			if (templateLocation == null) {
				IStatus status = new Status(IStatus.WARNING, PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_READING_FILE, NLS.bind(Messages.error_readingDirectory, fragmentPath), null);
				BundleHelper.getDefault().getLog().log(status);
				return;
			}

			//Copy the fragment.xml
			try {
				InputStream fragmentXML = BundleHelper.getDefault().getBundle().getEntry(TEMPLATE + "/30/fragment/fragment.xml").openStream(); //$NON-NLS-1$
				Utils.transferStreams(fragmentXML, new FileOutputStream(sourceFragmentDirURL.append(Constants.FRAGMENT_FILENAME_DESCRIPTOR).toOSString()));
			} catch (IOException e1) {
				String message = NLS.bind(Messages.exception_readingFile, TEMPLATE + "/30/fragment/fragment.xml"); //$NON-NLS-1$
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e1));
			}

			StringBuffer buffer = Utils.readFile(templateLocation.openStream());
			//Set the Id of the fragment
			int beginId = Utils.scan(buffer, 0, REPLACED_FRAGMENT_ID);
			buffer.replace(beginId, beginId + REPLACED_FRAGMENT_ID.length(), fragment.getId());
			//		set the version number
			beginId = Utils.scan(buffer, beginId, REPLACED_FRAGMENT_VERSION);
			buffer.replace(beginId, beginId + REPLACED_FRAGMENT_VERSION.length(), fragment.getVersion());
			// Set the Id of the plugin for the fragment
			beginId = Utils.scan(buffer, beginId, REPLACED_PLUGIN_ID);
			buffer.replace(beginId, beginId + REPLACED_PLUGIN_ID.length(), plugin.getId());
			//		set the version number of the plugin to which the fragment is attached to
			BundleDescription effectivePlugin = getSite().getRegistry().getResolvedBundle(plugin.getId(), plugin.getVersion());
			beginId = Utils.scan(buffer, beginId, REPLACED_PLUGIN_VERSION);
			buffer.replace(beginId, beginId + REPLACED_PLUGIN_VERSION.length(), effectivePlugin.getVersion().toString());
			// Set the platform filter of the fragment
			beginId = Utils.scan(buffer, beginId, REPLACED_PLATFORM_FILTER);
			buffer.replace(beginId, beginId + REPLACED_PLATFORM_FILTER.length(), "(& (osgi.ws=" + fragment.getWS() + ") (osgi.os=" + fragment.getOS() + ") (osgi.arch=" + fragment.getArch() + "))"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

			Utils.transferStreams(new ByteArrayInputStream(buffer.toString().getBytes()), new FileOutputStream(sourceFragmentDirURL.append(Constants.BUNDLE_FILENAME_DESCRIPTOR).toOSString()));
			Collection copiedFiles = Utils.copyFiles(featureRootLocation + '/' + "sourceTemplateFragment", sourceFragmentDir.getAbsolutePath()); //$NON-NLS-1$
			if (copiedFiles.contains(Constants.BUNDLE_FILENAME_DESCRIPTOR)) {
				//make sure the manifest.mf has the versions we want
				replaceManifestValue(sourceFragmentDirURL.append(Constants.BUNDLE_FILENAME_DESCRIPTOR).toOSString(), org.osgi.framework.Constants.BUNDLE_VERSION, fragment.getVersion());
				String host = plugin.getId() + ';' + org.osgi.framework.Constants.BUNDLE_VERSION + '=' + effectivePlugin.getVersion().toString();
				replaceManifestValue(sourceFragmentDirURL.append(Constants.BUNDLE_FILENAME_DESCRIPTOR).toOSString(), org.osgi.framework.Constants.FRAGMENT_HOST, host);
			}
			File buildProperty = sourceFragmentDirURL.append(PROPERTIES_FILE).toFile();
			if (!buildProperty.exists()) { //If a build.properties file already exist  then we don't override it.
				copiedFiles.add(Constants.FRAGMENT_FILENAME_DESCRIPTOR); //Because the fragment.xml is not copied, we need to add it to the file
				copiedFiles.add("src/**"); //$NON-NLS-1$
				copiedFiles.add(Constants.BUNDLE_FILENAME_DESCRIPTOR);
				Properties sourceBuildProperties = new Properties();
				sourceBuildProperties.put(PROPERTY_BIN_INCLUDES, Utils.getStringFromCollection(copiedFiles, ",")); //$NON-NLS-1$
				sourceBuildProperties.put("sourcePlugin", "true"); //$NON-NLS-1$ //$NON-NLS-2$
				try {
					OutputStream buildFile = new BufferedOutputStream(new FileOutputStream(buildProperty));
					try {
						sourceBuildProperties.store(buildFile, null);
					} finally {
						buildFile.close();
					}
				} catch (FileNotFoundException e) {
					String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
					throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
				} catch (IOException e) {
					String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
					throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
				}
			}
		} catch (IOException e) {
			String message = NLS.bind(Messages.exception_writingFile, sourceFragmentDir.getName());
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, null));
		}
		PDEState state = getSite().getRegistry();
		BundleDescription oldBundle = state.getResolvedBundle(fragment.getId());
		if (oldBundle != null)
			state.getState().removeBundle(oldBundle);
		state.addBundle(sourceFragmentDir);
	}

	private void createSourceFragment(FeatureEntry fragment, FeatureEntry plugin) throws CoreException {
		// create the directory for the plugin
		Path sourceFragmentDirURL = new Path(getWorkingDirectory() + '/' + DEFAULT_PLUGIN_LOCATION + '/' + getSourcePluginName(fragment, false));
		File sourceFragmentDir = new File(sourceFragmentDirURL.toOSString());
		sourceFragmentDir.mkdirs();
		try {
			// read the content of the template file
			Path fragmentPath = new Path(TEMPLATE + "/21/fragment/" + Constants.FRAGMENT_FILENAME_DESCRIPTOR);//$NON-NLS-1$
			URL templateLocation = BundleHelper.getDefault().find(fragmentPath);
			if (templateLocation == null) {
				IStatus status = new Status(IStatus.WARNING, PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_READING_FILE, NLS.bind(Messages.error_readingDirectory, fragmentPath), null);
				BundleHelper.getDefault().getLog().log(status);
				return;
			}

			StringBuffer buffer = Utils.readFile(templateLocation.openStream());
			//Set the Id of the fragment
			int beginId = Utils.scan(buffer, 0, REPLACED_FRAGMENT_ID);
			buffer.replace(beginId, beginId + REPLACED_FRAGMENT_ID.length(), fragment.getId());
			//		set the version number
			beginId = Utils.scan(buffer, beginId, REPLACED_FRAGMENT_VERSION);
			buffer.replace(beginId, beginId + REPLACED_FRAGMENT_VERSION.length(), fragment.getVersion());
			// Set the Id of the plugin for the fragment
			beginId = Utils.scan(buffer, beginId, REPLACED_PLUGIN_ID);
			buffer.replace(beginId, beginId + REPLACED_PLUGIN_ID.length(), plugin.getId());
			//		set the version number of the plugin to which the fragment is attached to
			beginId = Utils.scan(buffer, beginId, REPLACED_PLUGIN_VERSION);
			buffer.replace(beginId, beginId + REPLACED_PLUGIN_VERSION.length(), plugin.getVersion());
			Utils.transferStreams(new ByteArrayInputStream(buffer.toString().getBytes()), new FileOutputStream(sourceFragmentDirURL.append(Constants.FRAGMENT_FILENAME_DESCRIPTOR).toOSString()));
			Collection copiedFiles = Utils.copyFiles(featureRootLocation + '/' + "sourceTemplateFragment", sourceFragmentDir.getAbsolutePath()); //$NON-NLS-1$
			if (copiedFiles.contains(Constants.FRAGMENT_FILENAME_DESCRIPTOR)) {
				replaceXMLAttribute(sourceFragmentDirURL.append(Constants.FRAGMENT_FILENAME_DESCRIPTOR).toOSString(), FRAGMENT_START_TAG, VERSION, fragment.getVersion());
				replaceXMLAttribute(sourceFragmentDirURL.append(Constants.FRAGMENT_FILENAME_DESCRIPTOR).toOSString(), FRAGMENT_START_TAG, PLUGIN_VERSION, plugin.getVersion());
			}
			File buildProperty = sourceFragmentDirURL.append(PROPERTIES_FILE).toFile();
			if (!buildProperty.exists()) { //If a build.properties file already exist  then we don't override it.
				copiedFiles.add(Constants.FRAGMENT_FILENAME_DESCRIPTOR); //Because the fragment.xml is not copied, we need to add it to the file
				copiedFiles.add("src/**"); //$NON-NLS-1$
				Properties sourceBuildProperties = new Properties();
				sourceBuildProperties.put(PROPERTY_BIN_INCLUDES, Utils.getStringFromCollection(copiedFiles, ",")); //$NON-NLS-1$
				sourceBuildProperties.put("sourcePlugin", "true"); //$NON-NLS-1$ //$NON-NLS-2$
				try {
					OutputStream buildFile = new BufferedOutputStream(new FileOutputStream(buildProperty));
					try {
						sourceBuildProperties.store(buildFile, null);
					} finally {
						buildFile.close();
					}
				} catch (FileNotFoundException e) {
					String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
					throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
				} catch (IOException e) {
					String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
					throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
				}
			}
		} catch (IOException e) {
			String message = NLS.bind(Messages.exception_writingFile, sourceFragmentDir.getName());
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, null));
		}
		PDEState state = getSite().getRegistry();
		BundleDescription oldBundle = state.getResolvedBundle(fragment.getId());
		if (oldBundle != null)
			state.getState().removeBundle(oldBundle);
		state.addBundle(sourceFragmentDir);
	}

	private void writeSourceFeature(BuildTimeFeature sourceFeature) throws CoreException {
		String sourceFeatureDir = getWorkingDirectory() + '/' + DEFAULT_FEATURE_LOCATION + '/' + sourceFeatureId;
		File sourceDir = new File(sourceFeatureDir);
		sourceDir.mkdirs();
		// write the source feature to the feature.xml
		File file = new File(sourceFeatureDir + '/' + Constants.FEATURE_FILENAME_DESCRIPTOR);
		try {
			SourceFeatureWriter writer = new SourceFeatureWriter(new BufferedOutputStream(new FileOutputStream(file)), sourceFeature, getSite());
			try {
				writer.printFeature();
			} finally {
				writer.close();
			}
		} catch (IOException e) {
			String message = NLS.bind(Messages.error_creatingFeature, sourceFeature.getId());
			throw new CoreException(new Status(IStatus.OK, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		}
		Collection copiedFiles = Utils.copyFiles(featureRootLocation + '/' + "sourceTemplateFeature", sourceFeatureDir); //$NON-NLS-1$
		if (copiedFiles.contains(Constants.FEATURE_FILENAME_DESCRIPTOR)) {
			//we overwrote our feature.xml with a template, replace the version
			replaceXMLAttribute(sourceFeatureDir + '/' + Constants.FEATURE_FILENAME_DESCRIPTOR, FEATURE_START_TAG, VERSION, sourceFeature.getVersion());
		}
		File buildProperty = new File(sourceFeatureDir + '/' + PROPERTIES_FILE);
		if (buildProperty.exists()) {//If a build.properties file already exist then we don't override it.
			getSite().addFeatureReferenceModel(sourceDir);
			return;
		}
		copiedFiles.add(Constants.FEATURE_FILENAME_DESCRIPTOR); //Because the feature.xml is not copied, we need to add it to the file
		Properties sourceBuildProperties = new Properties();
		sourceBuildProperties.put(PROPERTY_BIN_INCLUDES, Utils.getStringFromCollection(copiedFiles, ",")); //$NON-NLS-1$
		OutputStream output = null;
		try {
			output = new BufferedOutputStream(new FileOutputStream(buildProperty));
			try {
				sourceBuildProperties.store(output, null);
			} finally {
				output.close();
			}
		} catch (FileNotFoundException e) {
			String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		} catch (IOException e) {
			String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		}
		getSite().addFeatureReferenceModel(sourceDir);
	}

	private void replaceXMLAttribute(String location, String tag, String attr, String newValue) {
		File featureFile = new File(location);
		if (!featureFile.exists())
			return;

		StringBuffer buffer = null;
		try {
			buffer = Utils.readFile(featureFile);
		} catch (IOException e) {
			return;
		}

		int startComment = Utils.scan(buffer, 0, COMMENT_START_TAG);
		int endComment = startComment > -1 ? Utils.scan(buffer, startComment, COMMENT_END_TAG) : -1;
		int startTag = Utils.scan(buffer, 0, tag);
		while (startComment != -1 && startTag > startComment && startTag < endComment) {
			startTag = Utils.scan(buffer, endComment, tag);
			startComment = Utils.scan(buffer, endComment, COMMENT_START_TAG);
			endComment = startComment > -1 ? Utils.scan(buffer, startComment, COMMENT_END_TAG) : -1;
		}
		if (startTag == -1)
			return;
		int endTag = Utils.scan(buffer, startTag, ">"); //$NON-NLS-1$
		boolean attrFound = false;
		while (!attrFound) {
			int startAttributeWord = Utils.scan(buffer, startTag, attr);
			if (startAttributeWord == -1 || startAttributeWord > endTag)
				return;
			if (!Character.isWhitespace(buffer.charAt(startAttributeWord - 1))) {
				startTag = startAttributeWord + attr.length();
				continue;
			}
			//Verify that the word found is the actual attribute
			int endAttributeWord = startAttributeWord + attr.length();
			while (Character.isWhitespace(buffer.charAt(endAttributeWord)) && endAttributeWord < endTag) {
				endAttributeWord++;
			}
			if (endAttributeWord > endTag) { //attribute  has not been found
				return;
			}

			if (buffer.charAt(endAttributeWord) != '=') {
				startTag = endAttributeWord;
				continue;
			}

			int startVersionId = Utils.scan(buffer, startAttributeWord + 1, "\""); //$NON-NLS-1$
			int endVersionId = Utils.scan(buffer, startVersionId + 1, "\""); //$NON-NLS-1$
			buffer.replace(startVersionId + 1, endVersionId, newValue);
			attrFound = true;
		}
		if (attrFound) {
			try {
				Utils.transferStreams(new ByteArrayInputStream(buffer.toString().getBytes()), new FileOutputStream(featureFile));
			} catch (IOException e) {
				//ignore
			}
		}
	}

	private FeatureEntry createSourceBundle(BuildTimeFeature sourceFeature, FeatureEntry pluginEntry) throws CoreException {
		BundleDescription bundle = getSite().getRegistry().getBundle(pluginEntry.getId(), pluginEntry.getVersion(), true);
		if (bundle == null) {
			getSite().missingPlugin(pluginEntry.getId(), pluginEntry.getVersion(), true);
		}

		if (excludedEntries != null && excludedEntries.containsKey(bundle.getSymbolicName())) {
			List excludedVersions = (List) excludedEntries.get(bundle.getSymbolicName());
			for (Iterator iterator = excludedVersions.iterator(); iterator.hasNext();) {
				Version version = (Version) iterator.next();
				if (Utils.matchVersions(bundle.getVersion().toString(), version.toString()))
					return null;
			}
		}

		Properties bundleProperties = getBuildProperties(bundle);
		if (!Boolean.valueOf(bundleProperties.getProperty(PROPERTY_GENERATE_SOURCE_BUNDLE, TRUE)).booleanValue()) {
			return null;
		}

		FeatureEntry sourceEntry = new FeatureEntry(pluginEntry.getId() + ".source", bundle.getVersion().toString(), true); //$NON-NLS-1$
		sourceEntry.setEnvironment(pluginEntry.getOS(), pluginEntry.getWS(), pluginEntry.getArch(), pluginEntry.getNL());
		sourceEntry.setUnpack(false);

		if (Utils.isBinary(bundle)) {
			//binary, don't generate a source bundle.  But we can add the source entry if we can find an already existing one
			BundleDescription sourceBundle = getSite().getRegistry().getResolvedBundle(sourceEntry.getId(), sourceEntry.getVersion());
			if (sourceBundle != null) {
				if (Utils.isSourceBundle(sourceBundle)) {
					//it is a source bundle, check that it is for bundle
					Map headerMap = Utils.parseSourceBundleEntry(sourceBundle);
					Map entryMap = (Map) headerMap.get(bundle.getSymbolicName());
					if (entryMap != null && bundle.getVersion().toString().equals(entryMap.get("version"))) { //$NON-NLS-1$
						sourceEntry.setUnpack(new File(sourceBundle.getLocation()).isDirectory());

						FeatureEntry existingEntry = sourceFeature.findPluginEntry(sourceEntry.getId(), sourceEntry.getVersion());
						if (existingEntry == null || existingEntry.getVersion() == GENERIC_VERSION_NUMBER) {
							if (existingEntry != null)
								sourceFeature.removeEntry(existingEntry);
							sourceFeature.addEntry(sourceEntry);
							return sourceEntry;
						}
						return existingEntry;
					}
				}
			}
			return null;
		}

		sourceFeature.addEntry(sourceEntry);

		generateSourcePlugin(sourceEntry, bundle);

		return sourceEntry;
	}

	public void generateSourcePlugin(FeatureEntry sourceEntry, BundleDescription originalBundle) throws CoreException {
		IPath sourcePluginDirURL = new Path(getWorkingDirectory() + '/' + DEFAULT_PLUGIN_LOCATION + '/' + getSourcePluginName(sourceEntry, false));

		Manifest manifest = new Manifest();
		Attributes attributes = manifest.getMainAttributes();
		attributes.put(Name.MANIFEST_VERSION, "1.0"); //$NON-NLS-1$
		attributes.put(new Name(org.osgi.framework.Constants.BUNDLE_MANIFESTVERSION), "2"); //$NON-NLS-1$
		attributes.put(new Name(org.osgi.framework.Constants.BUNDLE_NAME), originalBundle.getName());
		attributes.put(new Name(org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME), sourceEntry.getId());
		attributes.put(new Name(org.osgi.framework.Constants.BUNDLE_VERSION), originalBundle.getVersion().toString());
		attributes.put(new Name(ECLIPSE_SOURCE_BUNDLE), originalBundle.getSymbolicName() + ";version=\"" + originalBundle.getVersion().toString() + "\""); //$NON-NLS-1$ //$NON-NLS-2$
		if (originalBundle.getPlatformFilter() != null)
			attributes.put(new Name(ECLIPSE_PLATFORM_FILTER), originalBundle.getPlatformFilter());

		File manifestFile = new File(sourcePluginDirURL.toFile(), Constants.BUNDLE_FILENAME_DESCRIPTOR);
		manifestFile.getParentFile().mkdirs();
		BufferedOutputStream out = null;
		try {
			out = new BufferedOutputStream(new FileOutputStream(manifestFile));
			try {
				manifest.write(out);
			} finally {
				out.close();
			}
		} catch (IOException e) {
			String message = NLS.bind(Messages.exception_writingFile, manifestFile.getAbsolutePath());
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		}

		generateSourceFiles(sourcePluginDirURL, sourceEntry, "sourceTemplateBundle"); //$NON-NLS-1$

		PDEState state = getSite().getRegistry();
		BundleDescription oldBundle = state.getResolvedBundle(sourceEntry.getId());
		if (oldBundle != null)
			state.getState().removeBundle(oldBundle);
		state.addBundle(sourcePluginDirURL.toFile());

		director.sourceToGather.addElementEntry(sourceEntry.getId(), originalBundle);
	}

	private FeatureEntry create30SourcePlugin(BuildTimeFeature sourceFeature) throws CoreException {
		//Create an object representing the plugin
		FeatureEntry result = new FeatureEntry(sourceFeature.getId(), sourceFeature.getVersion(), true);
		sourceFeature.addEntry(result);

		// create the directory for the plugin
		IPath sourcePluginDirURL = new Path(getWorkingDirectory() + '/' + DEFAULT_PLUGIN_LOCATION + '/' + getSourcePluginName(result, false));
		File sourcePluginDir = sourcePluginDirURL.toFile();
		new File(sourcePluginDir, "META-INF").mkdirs(); //$NON-NLS-1$

		// Create the MANIFEST.MF
		StringBuffer buffer;
		Path templateManifest = new Path(TEMPLATE + "/30/plugin/" + Constants.BUNDLE_FILENAME_DESCRIPTOR); //$NON-NLS-1$
		URL templateManifestURL = BundleHelper.getDefault().find(templateManifest);
		if (templateManifestURL == null) {
			IStatus status = new Status(IStatus.WARNING, PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_READING_FILE, NLS.bind(Messages.error_readingDirectory, templateManifest), null);
			BundleHelper.getDefault().getLog().log(status);
			return null;
		}
		try {
			buffer = Utils.readFile(templateManifestURL.openStream());
		} catch (IOException e1) {
			String message = NLS.bind(Messages.exception_readingFile, templateManifestURL.toExternalForm());
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_READING_FILE, message, e1));
		}
		int beginId = Utils.scan(buffer, 0, REPLACED_PLUGIN_ID);
		buffer.replace(beginId, beginId + REPLACED_PLUGIN_ID.length(), result.getId());
		//set the version number
		beginId = Utils.scan(buffer, beginId, REPLACED_PLUGIN_VERSION);
		buffer.replace(beginId, beginId + REPLACED_PLUGIN_VERSION.length(), result.getVersion());
		try {
			Utils.transferStreams(new ByteArrayInputStream(buffer.toString().getBytes()), new FileOutputStream(sourcePluginDirURL.append(Constants.BUNDLE_FILENAME_DESCRIPTOR).toOSString()));
		} catch (IOException e1) {
			String message = NLS.bind(Messages.exception_writingFile, templateManifestURL.toExternalForm());
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_READING_FILE, message, e1));
		}

		//Copy the plugin.xml
		try {
			InputStream pluginXML = BundleHelper.getDefault().getBundle().getEntry(TEMPLATE + "/30/plugin/plugin.xml").openStream(); //$NON-NLS-1$
			Utils.transferStreams(pluginXML, new FileOutputStream(sourcePluginDirURL.append(Constants.PLUGIN_FILENAME_DESCRIPTOR).toOSString()));
		} catch (IOException e1) {
			String message = NLS.bind(Messages.exception_readingFile, TEMPLATE + "/30/plugin/plugin.xml"); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e1));
		}

		//Copy the other files
		generateSourceFiles(sourcePluginDirURL, result, "sourceTemplatePlugin"); //$NON-NLS-1$

		PDEState state = getSite().getRegistry();
		BundleDescription oldBundle = state.getResolvedBundle(result.getId());
		if (oldBundle != null)
			state.getState().removeBundle(oldBundle);
		state.addBundle(sourcePluginDir);

		return result;
	}

	private void generateSourceFiles(IPath sourcePluginDirURL, FeatureEntry sourceEntry, String templateDir) throws CoreException {
		Collection copiedFiles = Utils.copyFiles(featureRootLocation + '/' + templateDir, sourcePluginDirURL.toFile().getAbsolutePath());
		if (copiedFiles.contains(Constants.BUNDLE_FILENAME_DESCRIPTOR)) {
			//make sure the manifest.mf has the version we want
			replaceManifestValue(sourcePluginDirURL.append(Constants.BUNDLE_FILENAME_DESCRIPTOR).toOSString(), org.osgi.framework.Constants.BUNDLE_VERSION, sourceEntry.getVersion());
		}

		//	If a build.properties file already exist then we use it supposing it is correct.
		File buildProperty = sourcePluginDirURL.append(PROPERTIES_FILE).toFile();
		if (!buildProperty.exists()) {
			copiedFiles.add(Constants.PLUGIN_FILENAME_DESCRIPTOR); //Because the plugin.xml is not copied, we need to add it to the file
			copiedFiles.add("src/**"); //$NON-NLS-1$
			copiedFiles.add(Constants.BUNDLE_FILENAME_DESCRIPTOR);//Because the manifest.mf is not copied, we need to add it to the file
			Properties sourceBuildProperties = new Properties();
			sourceBuildProperties.put(PROPERTY_BIN_INCLUDES, Utils.getStringFromCollection(copiedFiles, ",")); //$NON-NLS-1$
			sourceBuildProperties.put(SOURCE_PLUGIN_ATTRIBUTE, "true"); //$NON-NLS-1$
			try {
				OutputStream buildFile = new BufferedOutputStream(new FileOutputStream(buildProperty));
				try {
					sourceBuildProperties.store(buildFile, null);
				} finally {
					buildFile.close();
				}
			} catch (FileNotFoundException e) {
				String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
			} catch (IOException e) {
				String message = NLS.bind(Messages.exception_writingFile, buildProperty.getAbsolutePath());
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
			}
		}
	}

	private void replaceManifestValue(String location, String attribute, String newVersion) {
		Manifest manifest = null;
		try {
			InputStream is = new BufferedInputStream(new FileInputStream(location));
			try {
				manifest = new Manifest(is);
			} finally {
				is.close();
			}
		} catch (IOException e) {
			return;
		}

		manifest.getMainAttributes().put(new Attributes.Name(attribute), newVersion);

		OutputStream os = null;
		try {
			os = new BufferedOutputStream(new FileOutputStream(location));
			try {
				manifest.write(os);
			} finally {
				os.close();
			}
		} catch (IOException e1) {
			//ignore
		}
	}
}
