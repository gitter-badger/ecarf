/**
 * The contents of this file may be used under the terms of the Apache License, Version 2.0
 * in which case, the provisions of the Apache License Version 2.0 are applicable instead of those above.
 *
 * Copyright 2014, Ecarf.io
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
package io.ecarf.core.reason.rulebased.owl2rl.rdfs;

import io.ecarf.core.reason.rulebased.GenericRule;
import io.ecarf.core.triple.SchemaURIType;
import io.ecarf.core.triple.TermType;
import io.ecarf.core.triple.Triple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;

/**
 * A rule that supports SQL like queries for instance 
 * triples matching the body of this rule
 * 
 * OWL 2 RL rule cax-sco - rdfs9
 * T(?c1, rdfs:subClassOf, ?c2)
 * T(?x, rdf:type, ?c1) ->
 * T(?x, rdf:type, ?c2)
 * 
 * @author Omer Dawelbeit (omerio)
 *
 */
public class CaxScoRule extends GenericRule {

    /**
     * return the select column
     * @return
     */
    @Override
    public List<String> select() {
        return Lists.newArrayList(TermType.subject);
    }

    /**
     * return the items to use in the select clause
     * @param schemaTriple
     * @return
     */
    @Override
    public Map<String, String> where(Triple schemaTriple) {
        Map<String, String> where = new HashMap<>();

        if(schemaTriple.isEncoded()) {

            where.put(TermType.object,  schemaTriple.getSubject().toString());
            where.put(TermType.predicate, Integer.toString(SchemaURIType.RDF_TYPE.id));

        } else {

            where.put(TermType.object, "\"" + schemaTriple.getSubject() + "\"");
            where.put(TermType.predicate, "\"" + SchemaURIType.RDF_TYPE.getUri() + "\"");
        }
        return where;
    }

    /**
     * Generate a triple if this rules fires in the format T(?x, rdf:type, ?c2)
     * @param schemaTriple
     * @param instanceTriple
     * @return
     */
    @Override
    public Triple head(Triple schemaTriple, Triple instanceTriple) {

        Object predicate;

        if(schemaTriple.isEncoded()) {
            predicate = (long) SchemaURIType.RDF_TYPE.id;
        } else {
            predicate = SchemaURIType.RDF_TYPE.getUri();
        }

        Triple triple = schemaTriple.create(instanceTriple.getSubject(), predicate, schemaTriple.getObject());
        triple.setInferred(true);
        return triple;
    }

}
