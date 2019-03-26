/**
 * (C) Copyright IBM Corp. 2016,2017,2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.persistence.test.common;

import static com.ibm.watsonhealth.fhir.model.util.FHIRUtil.coding;
import static com.ibm.watsonhealth.fhir.model.util.FHIRUtil.uri;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import com.ibm.watsonhealth.fhir.model.Coding;
import com.ibm.watsonhealth.fhir.model.ObjectFactory;
import com.ibm.watsonhealth.fhir.model.Patient;
import com.ibm.watsonhealth.fhir.model.Resource;
import com.ibm.watsonhealth.fhir.model.Uri;

public abstract class AbstractSearchAllTest extends AbstractPersistenceTest {
    protected ObjectFactory objFactory = new ObjectFactory();
    protected String patientId;
    protected String lastUpdated;

    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" })
    public void testCreatePatient() throws Exception {
        Patient patient = readResource(Patient.class, "Patient_JohnDoe.json");
        
        Coding tag = coding("http://ibm.com/watsonhealth/fhir/tag", "tag");
        Coding security = coding("http://ibm.com/watsonhealth/fhir/security", "security");
        Uri profile = uri("http://ibm.com/watsonhealth/fhir/profile/Profile");
        
        patient.setMeta(objFactory.createMeta().withTag(tag).withSecurity(security).withProfile(profile));
        
        persistence.create(getDefaultPersistenceContext(), patient);
        assertNotNull(patient);
        assertNotNull(patient.getId());
        assertNotNull(patient.getId().getValue());
        assertNotNull(patient.getMeta());
        assertNotNull(patient.getMeta().getVersionId().getValue());
        assertEquals("1", patient.getMeta().getVersionId().getValue());
        
        patientId = patient.getId().getValue();
        lastUpdated = patient.getMeta().getLastUpdated().getValue().toString();
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingId() throws Exception {
        List<Resource> resources = runQueryTest(Resource.class, persistence, "_id", patientId);
        assertNotNull(resources);
        assertTrue(resources.size() > 0);
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingLastUpdated() throws Exception {
        List<Resource> resources = runQueryTest(Resource.class, persistence, "_lastUpdated", lastUpdated);
        assertNotNull(resources);
        assertTrue(resources.size() > 0);
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingIdAndLastUpdated() throws Exception {
        Map<String, List<String>> queryParms = new HashMap<String, List<String>>();
        queryParms.put("_id", Collections.singletonList(patientId));
        queryParms.put("_lastUpdated", Collections.singletonList(lastUpdated));
        List<Resource> resources = runQueryTest(Resource.class, persistence, queryParms);
        assertNotNull(resources);
        assertTrue(resources.size() > 0);
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingInvalidIdAndLastUpdated() throws Exception {
        Map<String, List<String>> queryParms = new HashMap<String, List<String>>();
        queryParms.put("_id", Collections.singletonList("a-totally-stinking-phony-id"));
        queryParms.put("_lastUpdated", Collections.singletonList(lastUpdated));
        List<Resource> resources = runQueryTest(Resource.class, persistence, queryParms);
        assertNotNull(resources);
        assertTrue(resources.size() == 0);
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingMultipleIds() throws Exception {
        Map<String, List<String>> queryParms = new HashMap<String, List<String>>();
        queryParms.put("_id", Collections.singletonList(patientId + ",a-totally-stinking-phony-id"));
        List<Resource> resources = runQueryTest(Resource.class, persistence, queryParms);
        assertNotNull(resources);
        assertTrue(resources.size() > 0);
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingMultipleInvalidIds() throws Exception {
        Map<String, List<String>> queryParms = new HashMap<String, List<String>>();
        queryParms.put("_id", Collections.singletonList("a-totally-stinking-phony-id,a-second-phony-id"));
        List<Resource> resources = runQueryTest(Resource.class, persistence, queryParms);
        assertNotNull(resources);
        assertTrue(resources.size() == 0);
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingTag() throws Exception {
        List<Resource> resources = runQueryTest(Resource.class, persistence, "_tag", "http://ibm.com/watsonhealth/fhir/tag|tag");
        assertNotNull(resources);
        assertTrue(resources.size() > 0);
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingSecurity() throws Exception {
        List<Resource> resources = runQueryTest(Resource.class, persistence, "_security", "http://ibm.com/watsonhealth/fhir/security|security");
        assertNotNull(resources);
        assertTrue(resources.size() > 0);
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingProfile() throws Exception {
        List<Resource> resources = runQueryTest(Resource.class, persistence, "_profile", "http://ibm.com/watsonhealth/fhir/profile/Profile");
        assertNotNull(resources);
        assertTrue(resources.size() > 0);
    }
    
    @Test(groups = { "cloudant", "jpa", "jdbc", "jdbc-normalized" }, dependsOnMethods = { "testCreatePatient" })
    public void testSearchAllUsingElements() throws Exception {
        List<Resource> resources = runQueryTest(Resource.class, persistence, "_elements", "meta");
        assertNotNull(resources);
        assertTrue(resources.size() > 0);
    }
}
