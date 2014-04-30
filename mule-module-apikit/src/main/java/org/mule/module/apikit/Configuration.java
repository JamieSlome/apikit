/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.apikit;

import static org.raml.parser.rule.ValidationResult.Level.ERROR;
import static org.raml.parser.rule.ValidationResult.Level.WARN;
import static org.raml.parser.rule.ValidationResult.UNKNOWN;

import org.mule.api.MuleContext;
import org.mule.api.config.MuleProperties;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.endpoint.ImmutableEndpoint;
import org.mule.construct.Flow;
import org.mule.module.apikit.exception.ApikitRuntimeException;
import org.mule.util.BeanUtils;
import org.mule.util.IOUtils;
import org.mule.util.StringMessageUtils;
import org.mule.util.StringUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.raml.emitter.RamlEmitter;
import org.raml.model.Raml;
import org.raml.parser.loader.CompositeResourceLoader;
import org.raml.parser.loader.DefaultResourceLoader;
import org.raml.parser.loader.FileResourceLoader;
import org.raml.parser.loader.ResourceLoader;
import org.raml.parser.rule.NodeRuleFactory;
import org.raml.parser.rule.ValidationResult;
import org.raml.parser.visitor.RamlDocumentBuilder;
import org.raml.parser.visitor.RamlValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Configuration
{

    public static final String APPLICATION_RAML = "application/raml+yaml";
    public static final String BIND_ALL_HOST = "0.0.0.0";
    public static final String DEFAULT_CONSOLE_PATH = "console";
    private static final String CONSOLE_URL_FILE = "consoleurl";

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private String name;
    private String raml;
    private boolean consoleEnabled = true;
    private String consolePath = DEFAULT_CONSOLE_PATH;
    private boolean disableValidations;
    private List<FlowMapping> flowMappings = new ArrayList<FlowMapping>();
    private FlowConstruct flowConstruct;
    private Raml api;
    private String baseHost;
    private Map<String, String> apikitRaml = new ConcurrentHashMap<String, String>();
    private List<String> consoleUrls = new ArrayList<String>();
    private Map<String, Flow> restFlowMap;

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getRaml()
    {
        return raml;
    }

    public void setRaml(String raml)
    {
        this.raml = raml;
    }

    public boolean isConsoleEnabled()
    {
        return consoleEnabled;
    }

    public void setConsoleEnabled(boolean consoleEnabled)
    {
        this.consoleEnabled = consoleEnabled;
    }

    public String getConsolePath()
    {
        return consolePath;
    }

    public void setConsolePath(String consolePath)
    {
        this.consolePath = consolePath;
    }

    public boolean isDisableValidations()
    {
        return disableValidations;
    }

    public void setDisableValidations(boolean disableValidations)
    {
        this.disableValidations = disableValidations;
    }

    public List<FlowMapping> getFlowMappings()
    {
        return flowMappings;
    }

    public void setFlowMappings(List<FlowMapping> flowMappings)
    {
        this.flowMappings = flowMappings;
    }

    public Raml getApi()
    {
        return api;
    }

    /**
     * Returns the RAML descriptor of the API.
     * If the baseUri is bound to all interfaces (0.0.0.0) the host parameter
     * is used to rewrite the base uri with the actual host received.
     */
    public String getApikitRaml(String host)
    {
        if (!BIND_ALL_HOST.equals(baseHost))
        {
            return apikitRaml.get(baseHost);
        }

        String hostRaml = apikitRaml.get(host);
        if (hostRaml == null)
        {
            Raml clone;
            try
            {
                clone = (Raml) BeanUtils.cloneBean(api);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
            clone.setBaseUri(api.getBaseUri().replace(baseHost, host));
            hostRaml = new RamlEmitter().dump(clone);
            apikitRaml.put(host, hostRaml);
        }
        return hostRaml;
    }

    public void loadApiDefinition(MuleContext muleContext, FlowConstruct flowConstruct)
    {
        this.flowConstruct = flowConstruct;
        ResourceLoader loader = new DefaultResourceLoader();
        String appHome = muleContext.getRegistry().get(MuleProperties.APP_HOME_DIRECTORY_PROPERTY);
        if (appHome != null)
        {
            loader = new CompositeResourceLoader(new FileResourceLoader(appHome), loader);
        }
        initializeRestFlowMap(muleContext);
        validateRaml(loader);
        RamlDocumentBuilder builder = new RamlDocumentBuilder(loader);
        api = builder.build(getRaml());
        injectEndpointUri();
        flowMappings = new ArrayList<FlowMapping>();
        apikitRaml = new ConcurrentHashMap<String, String>();
        apikitRaml.put(baseHost, new RamlEmitter().dump(api));
    }

    protected void validateRaml(ResourceLoader resourceLoader)
    {
        NodeRuleFactory ruleFactory = new NodeRuleFactory(new ActionImplementedRuleExtension(restFlowMap));
        List<ValidationResult> results = RamlValidationService.createDefault(resourceLoader, ruleFactory).validate(getRaml());
        List<ValidationResult> errors = ValidationResult.getLevel(ERROR, results);
        if (!errors.isEmpty())
        {
            String msg = aggregateMessages(errors, "Invalid API descriptor -- errors found: ");
            throw new ApikitRuntimeException(msg);
        }
        List<ValidationResult> warnings = ValidationResult.getLevel(WARN, results);
        if (!warnings.isEmpty())
        {
            logger.warn(aggregateMessages(warnings, "API descriptor Warnings -- warnings found: "));
        }
    }

    private String aggregateMessages(List<ValidationResult> results, String header)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append(results.size()).append("\n\n");
        for (ValidationResult result : results)
        {
            sb.append(result.getMessage()).append(" -- ");
            sb.append(" file: ");
            sb.append(result.getIncludeName() != null ? result.getIncludeName() : getRaml());
            if (result.getLine() != UNKNOWN)
            {
                sb.append(" -- line ");
                sb.append(result.getLine());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void injectEndpointUri()
    {
        String address = getEndpointAddress(flowConstruct);
        api.setBaseUri(address);
        try
        {
            baseHost = new URI(address).getHost();
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    public String getEndpointAddress(FlowConstruct flowConstruct)
    {
        ImmutableEndpoint endpoint = (ImmutableEndpoint) ((Flow) flowConstruct).getMessageSource();
        String address = endpoint.getAddress();
        String path = endpoint.getEndpointURI().getPath();
        String scheme = endpoint.getEndpointURI().getScheme();
        String chAddress = System.getProperty("fullDomain");
        String chBaseUri = scheme + "://" + chAddress + path;
        if (logger.isDebugEnabled())
        {
            if (api != null)
            {
                logger.debug("yaml baseUri: " + api.getBaseUri());
            }
            logger.debug("mule baseUri: " + address);
            logger.debug("chub baseUri: " + chBaseUri);
        }
        if (chAddress != null)
        {
            address = chBaseUri;
        }
        if (address.endsWith("/"))
        {
            logger.debug("removing trailing slash from baseuri -> " + address);
            address = address.substring(0, address.length() - 1);
        }
        return address;
    }

    public void addConsoleUrl(String url)
    {
        if (StringUtils.isNotBlank(url))
        {
            consoleUrls.add(url);
        }
    }

    public void publishConsoleUrls(String parentDirectory)
    {
        File urlFile = new File(parentDirectory, CONSOLE_URL_FILE);
        FileWriter writer = null;
        try
        {
            if (!urlFile.exists())
            {
                urlFile.createNewFile();
            }
            writer = new FileWriter(urlFile, true);


            for (String consoleUrl : consoleUrls)
            {
                writer.write(consoleUrl + "\n");
            }

            writer.flush();
        }
        catch (IOException e)
        {
            logger.error("cannot publish console url for studio", e);
        }
        finally
        {
            IOUtils.closeQuietly(writer);
        }

        if (logger.isInfoEnabled())
        {
            for (String consoleUrl : consoleUrls)
            {
                String msg = String.format("APIKit Console URL: %s", consoleUrl);
                logger.info(StringMessageUtils.getBoilerPlate(msg));
            }
        }
    }

    private void initializeRestFlowMap(MuleContext muleContext)
    {
        if (restFlowMap == null)
        {
            restFlowMap = new HashMap<String, Flow>();
            Collection<Flow> flows = muleContext.getRegistry().lookupObjects(Flow.class);
            for (Flow flow : flows)
            {
                String key = getRestFlowKey(flow.getName());
                if (key != null)
                {
                    restFlowMap.put(key, flow);
                }
            }
            for (FlowMapping mapping : getFlowMappings())
            {
                restFlowMap.put(mapping.getAction() + ":" + mapping.getResource(), mapping.getFlow());
            }
            if (logger.isDebugEnabled())
            {
                logger.debug("==== RestFlows defined:");
                for (String key : restFlowMap.keySet())
                {
                    logger.debug("\t\t" + key);
                }
            }
        }
    }

    public Map<String, Flow> getRestFlowMap()
    {
        return restFlowMap;
    }

    private String getRestFlowKey(String name)
    {
        String[] coords = name.split(":");
        String[] methods = {"get", "put", "post", "delete", "head", "patch", "options"};
        if (coords.length < 2 || !Arrays.asList(methods).contains(coords[0]))
        {
            return null;
        }
        if (coords.length == 3 && !coords[2].equals(getName()))
        {
            return null;
        }
        return coords[0] + ":" + coords[1];
    }

}
