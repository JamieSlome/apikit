/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.apikit;

import static org.mule.transport.http.HttpConnector.HTTP_STATUS_PROPERTY;

import org.mule.api.MuleEvent;
import org.mule.exception.CatchMessagingExceptionStrategy;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.mule.module.apikit.exception.ApikitRuntimeException;

public class MappingExceptionListener extends CatchMessagingExceptionStrategy
{
    private int statusCode;
    private List<Class<?>> exceptions = new ArrayList<Class<?>>();

    public void setStatusCode(int statusCode)
    {
        this.statusCode = statusCode;
    }

    public void setExceptions(List<String> exceptions)
    {
        for(String exception : exceptions)
        {
            try
            {
                this.exceptions.add(Class.forName(exception));
            }
            catch (ClassNotFoundException e)
            {
                throw new ApikitRuntimeException(e);
            }
        }
    }

    @Override
    public boolean accept(MuleEvent event)
    {
        Throwable exception = event.getMessage().getExceptionPayload().getException();
        Map<Throwable, Object> visited = new IdentityHashMap<Throwable, Object>();
        while (exception != null && !visited.containsKey(exception))
        {
            for (Class declared : exceptions)
            {
                if (declared.isAssignableFrom(exception.getClass()))
                {
                    return true;
                }
            }
            visited.put(exception, null);
            exception = exception.getCause();
        }
        return false;
    }

    @Override
    protected MuleEvent afterRouting(Exception exception, MuleEvent event)
    {
        event.getMessage().setOutboundProperty(HTTP_STATUS_PROPERTY, statusCode);
        return event;
    }

    @Override
    public String toString()
    {
        return "MappingExceptionListener{" +
               "statusCode=" + statusCode +
               ", exceptions=" + exceptions +
               '}';
    }
}
