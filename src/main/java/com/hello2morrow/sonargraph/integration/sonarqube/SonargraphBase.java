/**
 * SonarQube Sonargraph Integration Plugin
 * Copyright (C) 2016-2018 hello2morrow GmbH
 * mailto: support AT hello2morrow DOT com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hello2morrow.sonargraph.integration.sonarqube;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;

import org.sonar.api.measures.Metric;
import org.sonar.api.measures.Metric.ValueType;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import com.hello2morrow.sonargraph.integration.access.controller.ControllerAccess;
import com.hello2morrow.sonargraph.integration.access.controller.IMetaDataController;
import com.hello2morrow.sonargraph.integration.access.foundation.ResultWithOutcome;
import com.hello2morrow.sonargraph.integration.access.foundation.Utility;
import com.hello2morrow.sonargraph.integration.access.model.IExportMetaData;
import com.hello2morrow.sonargraph.integration.access.model.IIssueType;
import com.hello2morrow.sonargraph.integration.access.model.IMetricId;
import com.hello2morrow.sonargraph.integration.access.model.IModule;
import com.hello2morrow.sonargraph.integration.access.model.IRootDirectory;
import com.hello2morrow.sonargraph.integration.access.model.ISoftwareSystem;
import com.hello2morrow.sonargraph.integration.access.model.Severity;
import com.hello2morrow.sonargraph.integration.access.persistence.CustomMetrics;
import com.hello2morrow.sonargraph.integration.access.persistence.CustomMetrics.CustomMetricsConsumer;
import com.hello2morrow.sonargraph.integration.access.persistence.CustomMetrics.CustomMetricsProvider;

final class SonargraphBase
{
    static final String SONARGRAPH_PLUGIN_KEY = "sonargraphintegration";
    static final String SONARGRAPH_PLUGIN_PRESENTATION_NAME = "Sonargraph Integration";
    static final String SONARGRAPH_RULE_TAG = "sonargraph-integration";
    static final String JAVA = "java";
    static final String METRIC_ID_PREFIX = "sg_i.";//There is a max length of 64 characters for metric keys

    static final String CONFIG_PREFIX = "sonar.sonargraph.integration";
    static final String XML_REPORT_FILE_PATH_KEY = CONFIG_PREFIX + ":" + "report.path";
    static final String XML_REPORT_FILE_PATH_DEFAULT = "target/sonargraph/sonargraph-sonarqube-report.xml";

    static final String WORKSPACE = "Workspace";
    static final String SCRIPT_ISSUE_CATEGORY = "ScriptBased";
    static final String SCRIPT_ISSUE_CATEGORY_PRESENTATION_NAME = "Script Based";
    static final String SCRIPT_ISSUE_NAME = "ScriptIssue";
    static final String SCRIPT_ISSUE_PRESENTATION_NAME = "Script Issue";

    private static final Logger LOGGER = Loggers.get(SonargraphBase.class);
    private static final String BUILT_IN_META_DATA_RESOURCE_PATH = "/com/hello2morrow/sonargraph/integration/sonarqube/ExportMetaData.xml";
    private static final List<String> IGNORE_ISSUE_TYPE_CATEGORIES = Arrays.asList(WORKSPACE, "InstallationConfiguration");
    private static final int MAX_LENGTH_DESCRIPTION = 255;

    private static CustomMetricsProvider customMetricsProvider = new CustomMetricsProvider()
    {
        @Override
        public String getHiddenDirectoryName()
        {
            return SONARGRAPH_PLUGIN_KEY;
        }

        @Override
        public void feedback(final Feedback feedback, final String message)
        {
            switch (feedback)
            {
            case ERROR:
                LOGGER.error(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + message);
                break;
            case WARNING:
                LOGGER.warn(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + message);
                break;
            case INFO:
                LOGGER.info(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + message);
                break;
            case SAVED:
                LOGGER.warn(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + message + ", the SonarQube server needs to be restarted");
                break;
            default:
                break;
            }
        };
    };

    private SonargraphBase()
    {
        super();
    }

    static void setCustomMetricsPropertiesProvider(final CustomMetricsProvider provider)
    {
        customMetricsProvider = provider;
    }

    static String createMetricKeyFromStandardName(final String metricIdName)
    {
        return METRIC_ID_PREFIX + Utility.convertMixedCaseStringToConstantName(metricIdName).replace(" ", "");
    }

    static String createCustomMetricKeyFromStandardName(final String softwareSystemName, final String metricIdName)
    {
        return CustomMetrics.createCustomMetricKeyFromStandardName(METRIC_ID_PREFIX, softwareSystemName, metricIdName);
    }

    static String toLowerCase(String input, final boolean firstLower)
    {
        if (input == null)
        {
            return "";
        }
        if (input.isEmpty())
        {
            return input;
        }
        if (input.length() == 1)
        {
            return firstLower ? input.toLowerCase() : input.toUpperCase();
        }
        input = input.toLowerCase();
        return firstLower ? input : Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }

    private static void setMetricDirection(final Double bestValue, final Double worstValue, final Metric.Builder metric)
    {
        if (bestValue > worstValue)
        {
            metric.setDirection(Metric.DIRECTION_BETTER);
        }
        else if (bestValue < worstValue)
        {
            metric.setDirection(Metric.DIRECTION_WORST);
        }
        else
        {
            metric.setDirection(Metric.DIRECTION_NONE);
        }
    }

    private static void setWorstValue(final Double worstValue, final Metric.Builder metric)
    {
        if (!worstValue.equals(Double.NaN) && !worstValue.equals(Double.POSITIVE_INFINITY) && !worstValue.equals(Double.NEGATIVE_INFINITY))
        {
            metric.setWorstValue(worstValue);
        }
    }

    private static void setBestValue(final Double bestValue, final Metric.Builder metric)
    {
        if (!bestValue.equals(Double.NaN) && !bestValue.equals(Double.POSITIVE_INFINITY) && !bestValue.equals(Double.NEGATIVE_INFINITY))
        {
            metric.setBestValue(bestValue);
        }
    }

    static Metric<Serializable> createMetric(final IMetricId metricId)
    {
        final Metric.Builder builder = new Metric.Builder(createMetricKeyFromStandardName(metricId.getName()), metricId.getPresentationName(),
                metricId.isFloat() ? Metric.ValueType.FLOAT : Metric.ValueType.INT)
                        .setDescription(Utility.trimDescription(metricId.getDescription(), MAX_LENGTH_DESCRIPTION))
                        .setDomain(SONARGRAPH_PLUGIN_PRESENTATION_NAME);

        setBestValue(metricId.getBestValue(), builder);
        setWorstValue(metricId.getWorstValue(), builder);
        setMetricDirection(metricId.getBestValue(), metricId.getWorstValue(), builder);

        return builder.create();
    }

    static Properties loadCustomMetrics()
    {
        return CustomMetrics.loadCustomMetrics(customMetricsProvider);
    }

    static void addCustomMetric(final ISoftwareSystem softwareSystem, final IMetricId metricId, final Properties customMetrics)
    {
        CustomMetrics.addCustomMetric(softwareSystem, metricId, customMetrics, MAX_LENGTH_DESCRIPTION);
    }

    static void saveCustomMetrics(final Properties customMetrics)
    {
        CustomMetrics.save(customMetricsProvider, customMetrics);
    }

    static String getNonEmptyString(final Object input)
    {
        if (input instanceof String && !((String) input).isEmpty())
        {
            return (String) input;
        }
        throw new IllegalArgumentException("Empty input");
    }

    static List<Metric<Serializable>> getCustomMetrics(final Properties customMetrics)
    {
        if (customMetrics.isEmpty())
        {
            return Collections.emptyList();
        }

        final List<Metric<Serializable>> metrics = new ArrayList<>(customMetrics.size());

        CustomMetrics.parse(customMetrics, METRIC_ID_PREFIX, MAX_LENGTH_DESCRIPTION, new CustomMetricsConsumer()
        {
            @Override
            public void parsedIntMetric(final String nextMetricKey, final String nextMetricPresentationName, final String description,
                    final Double nextBestValue, final Double nextWorstValue)
            {
                final Metric.Builder builder = new Metric.Builder(nextMetricKey, nextMetricPresentationName, ValueType.INT)
                        .setDescription(description).setDomain(SONARGRAPH_PLUGIN_PRESENTATION_NAME);
                setBestValue(nextBestValue, builder);
                setWorstValue(nextWorstValue, builder);
                setMetricDirection(nextBestValue, nextWorstValue, builder);
                metrics.add(builder.create());
            }

            @Override
            public void parsedFloatMetric(final String nextMetricKey, final String nextMetricPresentationName, final String description,
                    final Double nextBestValue, final Double nextWorstValue)
            {
                final Metric.Builder builder = new Metric.Builder(nextMetricKey, nextMetricPresentationName, ValueType.FLOAT)
                        .setDescription(description).setDomain(SONARGRAPH_PLUGIN_PRESENTATION_NAME);
                setBestValue(nextBestValue, builder);
                setWorstValue(nextWorstValue, builder);
                setMetricDirection(nextBestValue, nextWorstValue, builder);
                metrics.add(builder.create());
            }

            @Override
            public void unableToParseMetric(final String message)
            {
                LOGGER.warn(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + message);
            }
        });

        return metrics;
    }

    static List<Metric<Serializable>> getCustomMetrics()
    {
        return getCustomMetrics(loadCustomMetrics());
    }

    static IExportMetaData readBuiltInMetaData()
    {
        final String errorMsg = "Failed to load built in meta data from '" + BUILT_IN_META_DATA_RESOURCE_PATH + "'";
        try (InputStream inputStream = SonargraphBase.class.getResourceAsStream(BUILT_IN_META_DATA_RESOURCE_PATH))
        {
            if (inputStream != null)
            {
                final IMetaDataController controller = ControllerAccess.createMetaDataController();
                final ResultWithOutcome<IExportMetaData> result = controller.loadExportMetaData(inputStream, BUILT_IN_META_DATA_RESOURCE_PATH);
                if (result.isFailure())
                {
                    LOGGER.error(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + errorMsg + " - " + result.toString());
                }
                else
                {
                    return result.getOutcome();
                }
            }
            else
            {
                LOGGER.error(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + errorMsg);
            }
        }
        catch (final IOException ex)
        {
            LOGGER.error(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + errorMsg, ex);
        }

        return null;
    }

    static String createRuleKey(final String issueTypeName)
    {
        return Utility.convertMixedCaseStringToConstantName(issueTypeName).replace(" ", "_");
    }

    static String createRuleName(final String issueTypePresentationName)
    {
        return SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + issueTypePresentationName;
    }

    static String createRuleCategoryTag(final String categoryPresentationName)
    {
        return categoryPresentationName.replace(' ', '-').toLowerCase();
    }

    static boolean ignoreIssueType(final IIssueType issueType)
    {
        final String categoryName = issueType.getCategory().getName();

        for (final String next : IGNORE_ISSUE_TYPE_CATEGORIES)
        {
            if (next.equals(categoryName))
            {
                return true;
            }
        }

        return false;
    }

    static boolean isErrorOrWarningWorkspoceIssue(final IIssueType issueType)
    {
        return WORKSPACE.equals(issueType.getCategory().getName())
                && (Severity.ERROR.equals(issueType.getSeverity()) || Severity.WARNING.equals(issueType.getSeverity()));
    }

    static boolean isScriptIssue(final IIssueType issueType)
    {
        return SCRIPT_ISSUE_CATEGORY.equals(issueType.getCategory().getName());
    }

    private static String getIdentifyingPath(final File file)
    {
        try
        {
            return file.getCanonicalPath().replace('\\', '/');
        }
        catch (final IOException e)
        {
            return file.getAbsolutePath().replace('\\', '/');
        }
    }

    private static List<IModule> getModuleCandidates(final ISoftwareSystem softwareSystem, final File baseDirectory)
    {
        final String identifyingBaseDirectoryPath = getIdentifyingPath(baseDirectory);
        final File systemBaseDirectory = new File(softwareSystem.getBaseDir());

        LOGGER.info(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": Trying to match module using system base directory '" + systemBaseDirectory + "'");

        final TreeMap<Integer, List<IModule>> numberOfMatchedRootDirsToModules = new TreeMap<>();
        for (final IModule nextModule : softwareSystem.getModules().values())
        {
            int matchedRootDirs = 0;

            for (final IRootDirectory nextRootDirectory : nextModule.getRootDirectories())
            {
                final String nextRelPath = nextRootDirectory.getRelativePath();
                final File nextAbsoluteRootDirectory = new File(systemBaseDirectory, nextRelPath);
                if (nextAbsoluteRootDirectory.exists())
                {
                    final String nextIdentifyingPath = getIdentifyingPath(nextAbsoluteRootDirectory);
                    if (nextIdentifyingPath.startsWith(identifyingBaseDirectoryPath))
                    {
                        LOGGER.info(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": Matched root directory '" + nextIdentifyingPath + "' underneath '"
                                + identifyingBaseDirectoryPath + "'");
                        matchedRootDirs++;
                    }
                }
            }

            if (matchedRootDirs > 0)
            {
                final Integer nextMatchedRootDirsAsInteger = Integer.valueOf(matchedRootDirs);
                final List<IModule> nextMatched = numberOfMatchedRootDirsToModules.computeIfAbsent(nextMatchedRootDirsAsInteger,
                        k -> new ArrayList<>(2));
                nextMatched.add(nextModule);
            }
        }

        if (!numberOfMatchedRootDirsToModules.isEmpty())
        {
            return numberOfMatchedRootDirsToModules.lastEntry().getValue();
        }

        return Collections.emptyList();
    }

    static IModule matchModule(final ISoftwareSystem softwareSystem, final String inputModuleKey, final File baseDirectory)
    {
        IModule matched = null;

        final List<IModule> moduleCandidates = getModuleCandidates(softwareSystem, baseDirectory);
        if (moduleCandidates.size() == 1)
        {
            matched = moduleCandidates.get(0);
        }

        if (matched == null)
        {
            LOGGER.info(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": No module match found for '" + inputModuleKey + "'");
        }
        else
        {
            LOGGER.info(SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": Matched module '" + matched.getName() + "'");
        }

        return matched;
    }
}