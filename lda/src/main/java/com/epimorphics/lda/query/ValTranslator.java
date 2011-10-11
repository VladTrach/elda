/*
    See lda-top/LICENCE (or http://elda.googlecode.com/hg/LICENCE)
    for the licence for this software.
    
    (c) Copyright 2011 Epimorphics Limited
    $Id$
*/

package com.epimorphics.lda.query;

import com.epimorphics.jsonrdf.Context.Prop;
import com.epimorphics.jsonrdf.RDFUtil;
import com.epimorphics.lda.core.Param.Info;
import com.epimorphics.lda.core.VarSupply;
import com.epimorphics.lda.rdfq.Any;
import com.epimorphics.lda.rdfq.Apply;
import com.epimorphics.lda.rdfq.Infix;
import com.epimorphics.lda.rdfq.RDFQ;
import com.epimorphics.lda.rdfq.RenderExpression;
import com.epimorphics.lda.rdfq.Variable;
import com.epimorphics.lda.shortnames.ShortnameService;
import com.hp.hpl.jena.vocabulary.OWL;
import com.hp.hpl.jena.vocabulary.RDFS;

/**
    ValTranslator handles the translation of the V in ?P=V
    to a suitable RDFQ node, usually (but not always) some
    kind of literal. It may need to add new expressions to
    the query filter (via the Expressions member), generate new
    variables (via the VarSupply member), and look at the
    type information of properties (via the ShortnameService
    member).
    
 	@author chris
*/
public class ValTranslator {
	
	public interface Filters {
		public void add( RenderExpression e );
	}
	
	protected final ShortnameService sns;
	protected final VarSupply vs;
	protected final Filters expressions;
	
	public ValTranslator(VarSupply vs, Filters expressions, ShortnameService sns) {
		this.vs = vs;
		this.sns = sns;
		this.expressions = expressions;
	}

	private static final String[] JUSTEMPTY = new String[]{""};

	public Any objectForValue( Info inf, String val, String languages ) {
	    String prop = inf.shortName;
		String[] langArray = languages == null ? JUSTEMPTY : languages.split( "," );
		String type = inf.typeURI;
		String expanded = sns.expand(val);
	//
//		if (type == null) {
//			if (langArray.length == 1) 
//				return RDFQ.literal( val, langArray[0], "" );
//			Variable o = vs.newVar();
//			Apply stringOf = RDFQ.apply( "str", o );
//			Infix equals = RDFQ.infix( stringOf, "=", RDFQ.literal( val ) );
//			Infix filter = RDFQ.infix( equals, "&&", ValTranslator.someOf( o, langArray ) );
//			expressions.add( filter );
//			return o;
//		}
		if (expanded != null) {
			return RDFQ.uri( expanded );
		} else if (type != null) {
            if (type.equals(OWL.Thing.getURI()) || type.equals(RDFS.Resource.getURI())) {
                return RDFQ.uri( val ); 
            } else if (sns.isDatatype( type )) {
            	if (!type.equals( RDFUtil.RDFPlainLiteral ))
            		return RDFQ.literal( val, null, type );
            }	    
            return RDFQ.literal( val, langArray[0], "" );
		} else if (langArray.length == 1) {
			return valueAsRDFQ( prop, val, langArray[0] );
		} else {
			Variable o = vs.newVar();
			Apply stringOf = RDFQ.apply( "str", o );
			Infix equals = RDFQ.infix( stringOf, "=", RDFQ.literal( val ) );
			Infix filter = RDFQ.infix( equals, "&&", ValTranslator.someOf( o, langArray ) );
			expressions.add( filter );
			return o;
	    }
	}

	private static boolean equals( String a, String b ) {
		return a == null ? b == null : a.equals(b);
	}

	private Any valueAsRDFQ( String p, String nodeValue, String language) {
	    Prop prop = sns.asContext().getPropertyByName( p ); 
	    if (prop == null) {
	        if (RDFUtil.looksLikeURI( nodeValue )) {
	            return RDFQ.uri( nodeValue ); 
	        }
	    } else {
	        String type = prop.getType();
	        if (type != null) {
	            if (type.equals(OWL.Thing.getURI()) || type.equals(RDFS.Resource.getURI())) {
	                return RDFQ.uri(nodeValue); 
	            } else if (sns.isDatatype( type )) {
	            	if (!type.equals( RDFUtil.RDFPlainLiteral ))
	            		return RDFQ.literal( nodeValue, null, type );
	            }
	        }
	    }
	    return RDFQ.literal( nodeValue, language, "" );
	}

	/**
	 	Generates lang(v) = l1 || lang(v) = l2 ... for each l1... in langArray.
	*/
	static RenderExpression someOf( Any v, String[] langArray ) {
		Apply langOf = RDFQ.apply( "lang", v );
		RenderExpression result = RDFQ.infix( langOf, "=", ValTranslator.omitNone( langArray[0] ) );
		for (int i = 1; i < langArray.length; i += 1)
			result = RDFQ.infix( result, "||", RDFQ.infix( langOf, "=", ValTranslator.omitNone( langArray[i] ) ) );
		return result;
	}

	/**
	     Answers lang unless it is "none", in which case it answers "".
	*/
	static Any omitNone( String lang ) {
		return RDFQ.literal( lang.equals( "none" ) ? "" : lang );
	}

}
