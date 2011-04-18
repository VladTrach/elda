/*
    See lda-top/LICENCE (or http://elda.googlecode.com/hg/LICENCE)
    for the licence for this software.
    
    (c) Copyright 2011 Epimorphics Limited
    $Id$

    File:        CallContext.java
    Created by:  Dave Reynolds
    Created on:  31 Jan 2010
*/

package com.epimorphics.lda.core;

import java.util.*;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.epimorphics.lda.bindings.Lookup;
import com.epimorphics.lda.bindings.Value;
import com.epimorphics.lda.bindings.VarValues;

/**
 * Encapsulates all the information which define a particular
 * invocation of the API. 
 * 
 * @author <a href="mailto:der@epimorphics.com">Dave Reynolds</a>
 * @version $Revision: $
*/
public class CallContext implements Lookup {

    static Logger log = LoggerFactory.getLogger( CallContext.class );
    
    protected MultiMap<String, Value> parameters = new MultiMap<String, Value>();
    protected UriInfo uriInfo = null;
    
    public CallContext(UriInfo uriInfo) {
        this.uriInfo = uriInfo;
    }
    
    /**
        Copy the given call-context, but add any bindings from the map
        for unset parameters.
    */
    public CallContext( VarValues defaults, CallContext toCopy ) {
    	this.uriInfo = toCopy.uriInfo;
    	defaults.putInto( this.parameters );
    	this.parameters.putAll( toCopy.parameters );
    }
    
	public static CallContext createContext( UriInfo ui, VarValues bindings ) {
//		System.err.println( ">> bindings: " + bindings );
	    MultivaluedMap<String, String> queryParams = ui.getQueryParameters();
//	    System.err.println( ">> qp: " + queryParams );
	    CallContext cc = new CallContext( ui );
	    bindings.putInto( cc.parameters );
	    for (Map.Entry<String, List<String>> e : queryParams.entrySet()) {
	        String name = e.getKey();
			Value basis = cc.parameters.get( name );
			if (basis == null) basis = Value.emptyPlain;
	        for (String val : e.getValue())
				cc.parameters.add( name, basis.withValueString( val ) );
	    }
//	    System.err.println( ">> !parameters: " + cc.parameters );
	    return cc;
	}
	
	public Iterator<String> parameterNames() {
		return parameters.keyIterator();
	}

    /**
     * Return a single value for a parameter, if there are multiple values
     * the returned one may be arbitrary
     */
    @Override public String getStringValue( String param ) {
        Value v = parameters.get( param );
		return v == null ? uriInfo.getQueryParameters().getFirst( param ) : v.valueString();
    }
    
    /**
     	Return all the values for a parameter.
    */
    @Override public Set<String> getStringValues( String param ) {
        Set<Value> vs = parameters.getAll( param );
//        System.err.println( ">> " + parameters );
		Set<String> values = new HashSet<String>( uriInfo.getQueryParameters().get( param ) );
		return vs == null ? values : asStrings( vs );
    }
    
    private Set<String> asStrings(Set<Value> vs) {
    	Set<String> result = new HashSet<String>(vs.size());
    	for (Value v: vs) result.add( v.valueString() );
    	return result;
	}

	/**
     * Return the request URI information.
     */
    public UriInfo getUriInfo() {
        return uriInfo;
    }
    
    @Override public String toString() {
        return parameters.toString();
    }
    
    /**
     * Return a UriBuilder initialized from the query, to allow
     * modified versions of query to be generated
     */
    public UriBuilder getURIBuilder() {
       return uriInfo.getRequestUriBuilder(); 
    }
    
    /**
        Answer the set of filter names from the call context query parameters.
    */
    public Set<String> getFilterPropertyNames() {
    	return new HashSet<String>( uriInfo.getQueryParameters().keySet() );    	
    }

    /**
        Expand <code>valueString</code> by replacing "{name}" by the value
        of that name. Answer the updated string.
    */
	public String expandVariables( String valueString ) {
		return VarValues.expandVariables( this, valueString );			
	}
}

