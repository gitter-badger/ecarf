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


package io.ecarf.core.term;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;

/**
 * Represent a term dictionary holding URIs and Blank nodes as the key and the encoded integer as the value
 * Literals are not encoded by this dictionary. To differentiate between Ids for resources vs. blank nodes 
 * we use prime numbers for Blank nodes. {@link BigInteger} primality methods are used to check for ids used
 * by blank nodes. Another approach for blank nodes is that we use a fixed threshold for them, for example
 * from 100 to 1,000,000 are blank nodes, but this puts a superficial limit on the number of blank nodes we 
 * can have. Using prime number has the advantage of adding infinite number of blank nodes.
 *   
 * @author Omer Dawelbeit (omerio)
 *
 */
public class TermDictionaryConcurrent extends AbstractDictionary implements Serializable {

    private static final long serialVersionUID = 1314487458787673648L;

    private final static Log log = LogFactory.getLog(TermDictionaryConcurrent.class);


    /**
     * We use a Guava BiMap to provide an inverse lookup into the dictionary Map
     * to be able to lookup terms by id. I'm assuming key lookups in both directions is O(1)
     * @see http://docs.guava-libraries.googlecode.com/git/javadoc/com/google/common/collect/BiMap.html
     */
    private BiMap<Object, Object> dictionary = Maps.synchronizedBiMap(HashBiMap.create()); //HashBiMap.create();
    
    private AtomicInteger largestResourceId = new AtomicInteger(RESOURCE_ID_START);


    @Override
    public Integer get(String key) {

        return (Integer) this.dictionary.get(key);
    }


    @Override
    public String get(Integer value) {

        return (String) this.dictionary.inverse().get(value);
    }


    @Override
    public int size() {

        return this.dictionary.size();
    }


    @Override
    public void put(String key, Integer value) {
        this.dictionary.put(key, value);

    }


    /**
     * add a part to the dictionary (part of a URI or a blank node)
     * @param part
     */
    @Override
    public void add(String part) {
        if(!this.containsKey(part)) {
            
            int resourceId = this.largestResourceId.addAndGet(1);
            this.put(part, resourceId);
        }
    }

    /**
     * Add an entry to the dictionary
     * @param term
     * @param id
     */
    @Override
    protected void add(String term, Integer id) {

        if(!this.containsKey(term)) {
            
            int largest = this.largestResourceId.get();

            if(largest < id) {
                this.largestResourceId.set(id);
            }
            this.put(term, id);
        }
    }


    @Override
    public boolean containsKey(String key) {

        return this.dictionary.containsKey(key);
    }



}
