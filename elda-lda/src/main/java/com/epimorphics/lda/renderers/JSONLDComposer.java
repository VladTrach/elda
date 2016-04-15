package com.epimorphics.lda.renderers;

import java.util.*;

import com.epimorphics.jsonrdf.*;
import com.epimorphics.lda.vocabularies.API;
import com.epimorphics.lda.vocabularies.ELDA_API;
import com.hp.hpl.jena.datatypes.RDFDatatype;
import com.hp.hpl.jena.datatypes.xsd.XSDDatatype;
import com.hp.hpl.jena.datatypes.xsd.impl.XSDBaseNumericType;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.*;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;
import com.hp.hpl.jena.vocabulary.DCTerms;

public class JSONLDComposer {
			
	public static class Int {
		private int i = 0;

		public void inc() { i += 1;	}
		
		public int value() { return i; }
	}
	
	final Model model;
	final Resource root;
	final JSONWriterFacade jw;
	final Map<String, String> termBindings;
	final ReadContext context;
	
	final Map<Resource, JSONLDComposer.Int> refCount = new HashMap<Resource, JSONLDComposer.Int>();
	final Map<Resource, String> bnodes = new HashMap<Resource, String>();
	
	public static final String OTHERS = ELDA_API.NS + "others";
	public static final String RESULTS = ELDA_API.NS + "results";
	
	public static final String FORMAT = DCTerms.format.getURI();
	public static final String VERSION = ELDA_API.NS + "version";

	public static final Property pOTHERS = ResourceFactory.createProperty(OTHERS);
	public static final Property pRESULTS = ResourceFactory.createProperty(RESULTS);
	
	public static boolean isContentStatement(Statement s) {
		Property P = s.getPredicate();
		String U = P.getURI();
		return 
			!P.equals(JSONLDComposer.pRESULTS) 
			&& !P.equals(JSONLDComposer.pOTHERS)
			&& !U.equals(FORMAT)
			&& !U.equals(VERSION)
			;
	}
	
	public JSONLDComposer(Model model, Resource root, ReadContext context, Map<String, String> termBindings, JSONWriterFacade jw) {
		this.jw = jw;
		this.root = root;
		this.model = model;
		this.context = context;
		this.termBindings = termBindings;
		countObjectReferencesIn(model);
	}

	private void countObjectReferencesIn(Model m) {
		for (StmtIterator statements = m.listStatements(); statements.hasNext();) {
			Statement statement = statements.nextStatement();
			RDFNode O = statement.getObject();
			if (O.isURIResource()) {
				JSONLDComposer.Int count = refCount.get(O);
				if (count == null) refCount.put(O.asResource(), count = new Int());
				count.inc();
			}
		}
	}

	private boolean onlyReference(Resource r) {
		JSONLDComposer.Int count = refCount.get(r);
		return count == null ? true : count.value() < 2;
	}

	public void renderItems(List<Resource> items) {
		jw.object();
	//
		jw.key("@context");
		jw.object();
		composeContext();
		jw.endObject();
	//
		jw.key("format").value("linked-data-api");
		jw.key("version").value("0.2A");
	//
		jw.key("meta");
		renderResource(root);
	//
		jw.key("results");
		jw.array();
		Set<Resource> itemSet = new HashSet<Resource>();
		for (Resource i: items) {
			renderResource(i);
			itemSet.add(i);
		}
		jw.endArray();
	//
		jw.key("others");
		jw.array();
		for (Map.Entry<Resource, JSONLDComposer.Int> e: refCount.entrySet()) {
			Resource i = e.getKey();
			if (isFitForRendering(itemSet, e, i)) renderResource(i);
		}
		jw.endArray();
	//
		jw.endObject();
	}

	private boolean isFitForRendering(Set<Resource> itemSet, Map.Entry<Resource, JSONLDComposer.Int> e, Resource i) {
		return e.getValue().value() > 1 
		&& !itemSet.contains(i) 
		&& i.listProperties().hasNext()
		&& !i.equals(root);
	}

	private void renderResource(Resource r) {
		jw.object();
		jw.key("@id").value(getId(r));
		composeProperties(r);
		jw.endObject();
	}

	private void composeProperties(Resource r) {
		Map<Property, List<RDFNode>> properties = new HashMap<Property, List<RDFNode>>();
		for (Statement s: r.listProperties().toList()) {
			Property p = s.getPredicate();
			RDFNode o = s.getObject();
			List<RDFNode> values = properties.get(p);
			if (values == null) properties.put(p, values = new ArrayList<RDFNode>());
			values.add(o);
		}			
	//
		for (Map.Entry<Property, List<RDFNode>> e: properties.entrySet()) {
			Property p = e.getKey();
			if (!p.equals(API.items)){
				boolean compactSingular = !isMultiValued(p);
				List<RDFNode> values = e.getValue();
				jw.key(term(p.getURI()));					
				if (values.size() == 1 && compactSingular) {
					value(p, values.get(0));
				} else {
					jw.array();
					for (RDFNode o: values) value(p, o);
					jw.endArray();
				}
			}
		}
	}

	private boolean isMultiValued(Property p) {
		return context.findProperty(p).isMultivalued();
	}

	private void value(Property p, RDFNode n) {
		if (n.isResource()) {
			Resource r = n.asResource();
			if (RDFUtil.isList(r)) {
				jw.array();
				List<RDFNode> l = r.as(RDFList.class).asJavaList();
				for (RDFNode element: l) value(p, element);
				jw.endArray();
			} else if (onlyReference(r)) {
				renderResource(r);
			} else {
				String u = getId(r);
				jw.object();
				jw.key("@id").value(u);
				jw.endObject();
			}
		} else if (n.isAnon()) {
			// never gets here
			throw new RuntimeException("BOOM");
		} else {
			Literal l = n.asLiteral();
			String typeURI = l.getDatatypeURI();
			String lang = l.getLanguage();
			String spelling = l.getLexicalForm();    	
			RDFDatatype dt = l.getDatatype();

			if (typeURI == null) {
				if (lang.equals("")) {
					jw.value(spelling);
				} else {
					jw.key("@lang").value(lang);
					jw.key("@value").value(spelling);
					jw.endObject();
				}
			} else if (isStructured(p)) {
				jw.object();
				jw.key("@type").value(term(typeURI));
				jw.key("@value").value(spelling);
				jw.endObject();
			} else if (dt.equals( XSDDatatype.XSDboolean)) {
				jw.value(l.getBoolean());
			} else if (isFloatLike(dt)) {
				jw.value( Double.parseDouble( spelling ) );
			} else if (dt instanceof XSDBaseNumericType) {
				jw.value( Long.parseLong( spelling ) );
	        } else if (dt.equals( XSDDatatype.XSDdateTime) || dt.equals( XSDDatatype.XSDdate) ) {
	        	jw.value( RDFUtil.formatDateTime( l, true ) );
	        } else if (dt.equals( XSDDatatype.XSDanyURI)) {
	            jw.value( spelling );
	        } else if (dt.equals( XSDDatatype.XSDstring) ) { 
	            jw.value( spelling ); 
			} else {
				jw.object();
				jw.key("@type").value(term(typeURI));
				jw.key("@value").value(spelling);
				jw.endObject();
			}
		}
	}
	
	private boolean isStructured(Property p) {
		return context.findProperty(p).isStructured();
	}

	private String getId(Resource r) {
		if (r.isURIResource()) return r.getURI();
		String id = bnodes.get(r);
		if (id == null) bnodes.put(r, id = "_:B" + bnodes.size());
		return id;
	}

	private boolean isFloatLike(RDFDatatype dt) {
		return 
			dt.equals( XSDDatatype.XSDfloat) 
			|| dt.equals( XSDDatatype.XSDdouble) 
			|| dt.equals( XSDDatatype.XSDdecimal);
	}

	private String term(String uri) {
		String shortName = termBindings.get(uri);
		return shortName == null ? uri : shortName;
	}	
	
	private void composeContext() {
		Set<String> present = new HashSet<String>();
		ExtendedIterator<Triple> triples = model.getGraph().find(Node.ANY, Node.ANY, Node.ANY);
		while (triples.hasNext()) {
			Triple t = triples.next();
			if (t.getSubject().isURI()) present.add(t.getSubject().getURI());
			if (t.getPredicate().isURI()) present.add(t.getPredicate().getURI());
			if (t.getObject().isURI()) present.add(t.getObject().getURI());
		}
	//
		jw.key("@vocab").value("eh:/vocab/");
		jw.key("results").value(RESULTS);
		jw.key("others").value(OTHERS);
		jw.key("format").value(FORMAT);
		jw.key("version").value(VERSION);
//		jw.key("first").value("http://www.w3.org/1999/xhtml/vocab#first");
		jw.key("meta").value("eh:/vocab/fixup/meta");
//		jw.key("meta").value("eh:/vocab/fixup/meta");
		
		
		jw.key("termBinding").value("eh:/elda/termBinding");
	//
		for (Map.Entry<String, String> e: termBindings.entrySet()) {
			String URI = e.getKey(), shortName = e.getValue();
			if (present.contains(URI)) {
				ContextPropertyInfo cp = context.findProperty(ResourceFactory.createProperty(URI));
				String type = cp.getType();				
				jw.key(shortName);
				if (type == null) {
					jw.value(URI);
				} else {			
					jw.object();
					jw.key("@id").value(URI);
					jw.key("@type").value(type);
					jw.endObject();
				}
			}
		}
	}
	
}