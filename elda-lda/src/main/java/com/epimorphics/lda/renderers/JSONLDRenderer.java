package com.epimorphics.lda.renderers;

import java.io.*;
import java.util.*;

import com.epimorphics.jsonrdf.*;
import com.epimorphics.lda.bindings.Bindings;
import com.epimorphics.lda.core.APIEndpoint;
import com.epimorphics.lda.core.APIResultSet;
import com.epimorphics.lda.shortnames.CompleteContext.Mode;
import com.epimorphics.lda.shortnames.*;
import com.epimorphics.lda.support.Times;
import com.epimorphics.lda.vocabularies.API;
import com.epimorphics.util.MediaType;
import com.github.jsonldjava.core.*;
import com.github.jsonldjava.impl.TurtleTripleCallback;
import com.github.jsonldjava.jena.JenaJSONLD;
import com.github.jsonldjava.jena.JenaRDFParser;
import com.github.jsonldjava.utils.JsonUtils;
import com.hp.hpl.jena.datatypes.RDFDatatype;
import com.hp.hpl.jena.datatypes.xsd.XSDDatatype;
import com.hp.hpl.jena.datatypes.xsd.impl.XSDBaseNumericType;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.*;
import com.hp.hpl.jena.shared.WrappedException;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;

/**
	A renderer into JSON-LD.

	The renderer is a wrapping of com.github.jsonld-java. It supplies
	the LDA shortnames and property types to json-ld and generates
	"compact" JSON. It is not particularly human-readable as JSON goes
	and has little in common with the LDA JSON layout. It is expected
	that consumers of JSON-LD will use libraries to make traversal and
	inspection straightforward.
	
*/
public class JSONLDRenderer implements Renderer {

	final MediaType mt;
    final APIEndpoint ep;
    final boolean jsonUsesISOdate; // not currently used
    
	public JSONLDRenderer(Resource config, MediaType mt, APIEndpoint ep, ShortnameService sns, boolean jsonUsesISOdate) {
		this.ep = ep;
		this.mt = mt;
		this.jsonUsesISOdate = jsonUsesISOdate;
	}

	@Override public MediaType getMediaType(Bindings ignored) {
		return mt;
	}

	@Override public Mode getMode() {
		return Mode.PreferLocalnames;
	}
	
	static boolean byHand = true;
	
	static { 
		
		JenaJSONLD.init();
		
	}
	
	@Override public BytesOut render(Times t, Bindings rc, final Map<String, String> termBindings, final APIResultSet results) {
		final Model model = results.getMergedModel();
		ShortnameService sns = ep.getSpec().getAPISpec().getShortnameService();
		final ReadContext context = CompleteReadContext.create(sns.asContext(), termBindings );        
        final Resource root = results.getRoot().inModel(model);

		if (byHand) {
			return new BytesOutTimed() {
				
				@Override protected void writeAll(OutputStream os) {
					ByteArrayOutputStream keepos = new ByteArrayOutputStream();
					try {
						Writer w = new OutputStreamWriter(keepos, "UTF-8");
						JSONWriterFacade jw = new JSONWriterWrapper(w, true);
						Composer c = new Composer(model, root, context, termBindings, jw);
						c.renderItems(results.getResultList());
						w.flush();						
					//
						byte[] bytes = keepos.toByteArray();
						os.write(bytes);
						
						ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
						Model reconstituted = ModelFactory.createDefaultModel();
						System.err.println(">> ALPHA");
						reconstituted.read(bis, "", "JSON-LD");
						System.err.println(">> BETA");
						reconstituted.write(System.err, "ttl");
						
					} catch (Throwable e) {
						throw new WrappedException(e);
					}
				}
				
				@Override protected String getFormat() {
					return getPreferredSuffix();
				}

				@Override public String getPoison() {
					return JSONRenderer.JSON_POISON;
				}
			};
		}
		

        final RDFParser parser = new JenaRDFParser(); 
        
//        System.err.println(">> model as Turtle");
//        String [] lines = modelString.split("\n");
//        int n = 1;
//        for (String line: lines) {
//        	String number = ("" + (10000 + n++)).substring(1);
//        	System.err.println(number + " " + line);
//        }

        return new BytesOutTimed() {

			@Override protected void writeAll(OutputStream os) {
				try {
					Writer w = new OutputStreamWriter(os, "UTF-8");
					Object json = JsonLdProcessor.fromRDF(model, parser);
		            Object ldContext = makeContext(context, model, termBindings);
					Object compacted = JsonLdProcessor.compact(json, ldContext, new JsonLdOptions());					
					JsonUtils.writePrettyPrint(w, compacted);
					w.flush();
				} catch (Throwable e) {
					throw new WrappedException(e);
				} 
			}

			@Override protected String getFormat() {
				return getPreferredSuffix();
			}

			@Override public String getPoison() {
				return JSONRenderer.JSON_POISON;
			}
			
		};
	}
	
	static private Object makeContext(ReadContext cx, Model model, Map<String, String> termBindings) {
		Set<String> present = new HashSet<String>();
		ExtendedIterator<Triple> triples = model.getGraph().find(Node.ANY, Node.ANY, Node.ANY);
		while (triples.hasNext()) {
			Triple t = triples.next();
			if (t.getSubject().isURI()) present.add(t.getSubject().getURI());
			if (t.getPredicate().isURI()) present.add(t.getPredicate().getURI());
			if (t.getObject().isURI()) present.add(t.getObject().getURI());
		}
	//
		Map<String, Object> result = new HashMap<String, Object>();
		for (Map.Entry<String, String> e: termBindings.entrySet()) {
			String URI = e.getKey(), shortName = e.getValue();
			if (present.contains(URI)) {
				ContextPropertyInfo cp = cx.findProperty(ResourceFactory.createProperty(URI));
				String type = cp.getType();
				if (type == null) {
					result.put(shortName, URI);
				} else {			
					Map<String, String> struct = new HashMap<String, String>();
					struct.put("@id", URI);
					struct.put("@type", type);
					result.put(shortName, struct);
				}
			}
		}
		return result;
	}

	@Override public String getPreferredSuffix() {
		return "json-ld";
	}
	
	// ==================================================================
	
	public static class Composer {

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
		final Map<Resource, Int> refCount = new HashMap<Resource, Int>();
		final Map<Resource, String> bnodes = new HashMap<Resource, String>();
		
		public Composer(Model model, Resource root, ReadContext context, Map<String, String> termBindings, JSONWriterFacade jw) {
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
					Int count = refCount.get(O);
					if (count == null) refCount.put(O.asResource(), count = new Int());
					count.inc();
				}
			}
		}

		private boolean onlyReference(Resource r) {
			Int count = refCount.get(r);
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
			for (Map.Entry<Resource, Int> e: refCount.entrySet()) {
				Resource i = e.getKey();
				if (isFitForRendering(itemSet, e, i)) renderResource(i);
			}
			jw.endArray();
		//
			jw.endObject();
		}

		private boolean isFitForRendering(Set<Resource> itemSet, Map.Entry<Resource, Int> e, Resource i) {
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
	

}
