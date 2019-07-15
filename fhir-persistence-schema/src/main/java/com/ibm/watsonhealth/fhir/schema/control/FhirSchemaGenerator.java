/**
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.schema.control;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.ibm.watsonhealth.database.utils.model.GroupPrivilege;
import com.ibm.watsonhealth.database.utils.model.IDatabaseObject;
import com.ibm.watsonhealth.database.utils.model.NopObject;
import com.ibm.watsonhealth.database.utils.model.ObjectGroup;
import com.ibm.watsonhealth.database.utils.model.PhysicalDataModel;
import com.ibm.watsonhealth.database.utils.model.Privilege;
import com.ibm.watsonhealth.database.utils.model.RowArrayType;
import com.ibm.watsonhealth.database.utils.model.RowTypeBuilder;
import com.ibm.watsonhealth.database.utils.model.Sequence;
import com.ibm.watsonhealth.database.utils.model.SessionVariableDef;
import com.ibm.watsonhealth.database.utils.model.Table;
import com.ibm.watsonhealth.database.utils.model.Tablespace;
import com.ibm.watsonhealth.fhir.model.type.FHIRResourceType;

import static com.ibm.watsonhealth.fhir.schema.control.FhirSchemaConstants.*;

/**
 * Encapsules the generation of the FHIR schema artifacts
 * @author rarnold
 *
 */
public class FhirSchemaGenerator {

    // The schema holding all the data-bearing tables
    private final String schemaName;

    // The schema used for administration objects like the tenants table, variable etc
    private final String adminSchemaName;

    private static final String ADD_RESOURCE_TEMPLATE = "add_resource_template.sql";
    private static final String ADD_CODE_SYSTEM = "ADD_CODE_SYSTEM";
    private static final String ADD_PARAMETER_NAME = "ADD_PARAMETER_NAME";
    private static final String ADD_RESOURCE_TYPE = "ADD_RESOURCE_TYPE";
    private static final String ADD_ANY_RESOURCE = "ADD_ANY_RESOURCE";

    // Tags used to control how we manage privilege grants
    public static final String TAG_GRANT = "GRANT";
    public static final String TAG_RESOURCE_PROCEDURE = "RESOURCE_PROCEDURE";
    public static final String TAG_RESOURCE_TABLE = "RESOURCE_TABLE";
    public static final String TAG_SEQUENCE = "SEQUENCE";
    public static final String TAG_VARIABLE = "VARIABLE";

    // The max array size we use for array type parameters
    private static final int ARRAY_SIZE = 256;

    // A list of all the resource types
    private final List<String> resourceTypes = new ArrayList<>();

    // The common sequence used for allocated resource ids
    private Sequence fhirSequence;


    // The set of dependencies common to all of our resource procedures
    private Set<IDatabaseObject> procedureDependencies = new HashSet<>();

    private Table codeSystemsTable;
    private Table parameterNamesTable;
    private Table resourceTypesTable;

    // A NOP marker used to ensure procedures are only applied after all the create
    // table statements are applied - to avoid DB2 catalog deadlocks
    private IDatabaseObject allTablesComplete;

    // Privileges needed by the stored procedures
    private List<GroupPrivilege> procedurePrivileges = new ArrayList<>();

    // Privileges needed for access to the FHIR resource data tables
    private List<GroupPrivilege> resourceTablePrivileges = new ArrayList<>();

    // Privileges needed for reading the sv_tenant_id variable
    private List<GroupPrivilege> variablePrivileges = new ArrayList<>();

    // Privileges needed for using the fhir sequence
    private List<GroupPrivilege> sequencePrivileges = new ArrayList<>();

    private final Tablespace fhirTablespace;

    // Session variable from the admin schema. Everything depends on this
    private final SessionVariableDef sessionVariable;


    /**
     * Public constructor
     * @param schemaName
     */
    public FhirSchemaGenerator(String adminSchemaName, String schemaName, Tablespace fhirTablespace, SessionVariableDef sessionVariable) {
        this.adminSchemaName = adminSchemaName;
        this.schemaName = schemaName;
        this.fhirTablespace = fhirTablespace;
        this.sessionVariable = sessionVariable;

        // The FHIR user will need execute privileges on the following procedures
        procedurePrivileges.add(new GroupPrivilege(FhirSchemaConstants.FHIR_USER_GRANT_GROUP, Privilege.EXECUTE));
        resourceTablePrivileges.add(new GroupPrivilege(FhirSchemaConstants.FHIR_USER_GRANT_GROUP, Privilege.INSERT));
        resourceTablePrivileges.add(new GroupPrivilege(FhirSchemaConstants.FHIR_USER_GRANT_GROUP, Privilege.SELECT));
        resourceTablePrivileges.add(new GroupPrivilege(FhirSchemaConstants.FHIR_USER_GRANT_GROUP, Privilege.UPDATE));
        resourceTablePrivileges.add(new GroupPrivilege(FhirSchemaConstants.FHIR_USER_GRANT_GROUP, Privilege.DELETE));
        variablePrivileges.add(new GroupPrivilege(FhirSchemaConstants.FHIR_USER_GRANT_GROUP, Privilege.READ));
        sequencePrivileges.add(new GroupPrivilege(FhirSchemaConstants.FHIR_USER_GRANT_GROUP, Privilege.USAGE));

        // All the resource types we will create tables for
        for (FHIRResourceType.ValueSet rt: FHIRResourceType.ValueSet.values()) {
            resourceTypes.add(rt.value());
        }
    }

    /**
     * Create the schema using the given target
     * @param target
     */
    public void buildSchema(PhysicalDataModel model) {

        // Build the complete model first so that we know it's consistent
        addFhirSequence(model);
        addParameterNames(model);
        addCodeSystems(model);
        addResourceTypes(model);
        addResourceTables(model);

        // Make sure we have the row and array types defined
        // before we try and add the procedures
        addProcedureParameterTypes(model);

        // All the table objects and types should be ready now, so create our NOP
        // which is used as a single dependency for all procedures. This means
        // procedures won't start until all the create table/type etc statements
        // are done...hopefully reducing the number of deadlocks we see.
        this.allTablesComplete = new NopObject(schemaName, "allTablesComplete");
        this.allTablesComplete.addDependencies(procedureDependencies);
        model.addObject(allTablesComplete);

        // These procedures just depend on the table they are manipulating and the fhir sequence. But
        // to avoid deadlocks, we only apply them after all the tables are done, so we make all
        // procedures depend on the allTablesComplete marker.
        model.addProcedure(this.schemaName, ADD_CODE_SYSTEM, FhirSchemaConstants.INITIAL_VERSION, () -> SchemaGeneratorUtil.readTemplate(adminSchemaName, schemaName, ADD_CODE_SYSTEM.toLowerCase() + ".sql", null), Arrays.asList(fhirSequence, codeSystemsTable, allTablesComplete), procedurePrivileges);
        model.addProcedure(this.schemaName, ADD_PARAMETER_NAME, FhirSchemaConstants.INITIAL_VERSION, () -> SchemaGeneratorUtil.readTemplate(adminSchemaName, schemaName, ADD_PARAMETER_NAME.toLowerCase() + ".sql", null), Arrays.asList(fhirSequence, parameterNamesTable, allTablesComplete), procedurePrivileges);
        model.addProcedure(this.schemaName, ADD_RESOURCE_TYPE, FhirSchemaConstants.INITIAL_VERSION, () -> SchemaGeneratorUtil.readTemplate(adminSchemaName, schemaName, ADD_RESOURCE_TYPE.toLowerCase() + ".sql", null), Arrays.asList(fhirSequence, resourceTypesTable, allTablesComplete), procedurePrivileges);
        model.addProcedure(this.schemaName, ADD_ANY_RESOURCE, FhirSchemaConstants.INITIAL_VERSION, () -> SchemaGeneratorUtil.readTemplate(adminSchemaName, schemaName, ADD_ANY_RESOURCE.toLowerCase() + ".sql", null), Arrays.asList(fhirSequence, resourceTypesTable, allTablesComplete), procedurePrivileges);
    }



    /**
    CREATE TABLE resource_types (
      resource_type_id INT NOT NULL
      CONSTRAINT pk_resource_type PRIMARY KEY,
      resource_type   VARCHAR(64) NOT NULL
);

-- make sure resource_type values are unique
CREATE UNIQUE INDEX unq_resource_types_rt ON resource_types(resource_type);
     * 
     * @param model
     */
    protected void addResourceTypes(PhysicalDataModel model) {

        resourceTypesTable = Table.builder(schemaName, RESOURCE_TYPES)
                .setTenantColumnName(MT_ID)
                .addIntColumn(    RESOURCE_TYPE_ID,      false)
                .addVarcharColumn(   RESOURCE_TYPE,  64, false)
                .addUniqueIndex(IDX + "unq_resource_types_rt", RESOURCE_TYPE)
                .addPrimaryKey(RESOURCE_TYPES + "_PK", RESOURCE_TYPE_ID)
                .setTablespace(fhirTablespace)
                .addPrivileges(resourceTablePrivileges)
                .enableAccessControl(this.sessionVariable)
                .build(model);

        // TODO Table should be immutable, so add support to the Builder for this
        this.procedureDependencies.add(resourceTypesTable);
        model.addTable(resourceTypesTable);
        model.addObject(resourceTypesTable);
    }


    /**
     * Add the collection of tables for each of the listed
     * FHIR resource types
     * @param model
     */
    protected void addResourceTables(PhysicalDataModel model) {
        if (this.sessionVariable == null) {
            throw new IllegalStateException("Session variable must be defined before adding resource tables");
        }

        // The sessionVariable is used to enable access control on every table, so we
        // provide it as a dependency
        FhirResourceGroup frg = new FhirResourceGroup(model, this.schemaName, sessionVariable, this.procedureDependencies, this.fhirTablespace, this.resourceTablePrivileges);
        for (String resourceType: this.resourceTypes) {
            ObjectGroup group = frg.addResourceType(resourceType);

            // Add additional dependencies the group doesn't yet know about
            group.addDependencies(Arrays.asList(this.fhirTablespace, this.sessionVariable, this.codeSystemsTable, this.parameterNamesTable, this.resourceTypesTable));

            // Make this group a dependency for all the stored procedures.
            this.procedureDependencies.add(group);
            model.addObject(group);

        }
    }


    /**
     * Add the sequence objects to the given model object
     * -------------------------------------------------------------------------------


CREATE SEQUENCE test_sequence
             AS BIGINT
     START WITH 1
          CACHE 1000
       NO CYCLE;


     * @param model
     */
    protected void addSequences(PhysicalDataModel model) {

    }

    /**
     * 
     * 
CREATE TABLE parameter_names (
  parameter_name_id INT NOT NULL
                CONSTRAINT pk_parameter_name PRIMARY KEY,
  parameter_name   VARCHAR(255 OCTETS) NOT NULL
);

CREATE UNIQUE INDEX unq_parameter_name_rtnm ON parameter_names(parameter_name) INCLUDE (parameter_name_id);

     * @param model
     */
    protected void addParameterNames(PhysicalDataModel model) {

        // The index which also used by the database to support the primary key constraint
        String[] prfIndexCols = {PARAMETER_NAME};
        String[] prfIncludeCols = {PARAMETER_NAME_ID};

        parameterNamesTable = Table.builder(schemaName, PARAMETER_NAMES)
                .setTenantColumnName(MT_ID)
                .addIntColumn(     PARAMETER_NAME_ID,              false)
                .addVarcharColumn(    PARAMETER_NAME,         255, false)
                .addUniqueIndex(IDX + "PARAMETER_NAME_RTNM", Arrays.asList(prfIndexCols), Arrays.asList(prfIncludeCols))
                .addPrimaryKey(PARAMETER_NAMES + "_PK", PARAMETER_NAME_ID)
                .setTablespace(fhirTablespace)
                .addPrivileges(resourceTablePrivileges)
                .enableAccessControl(this.sessionVariable)
                .build(model);

        this.procedureDependencies.add(parameterNamesTable);

        model.addTable(parameterNamesTable);
        model.addObject(parameterNamesTable);
    }

    /**
     * Add the code_systems table to the database schema
CREATE TABLE code_systems (
  code_system_id         INT NOT NULL
   CONSTRAINT pk_code_system PRIMARY KEY,
  code_system_name       VARCHAR(255 OCTETS) NOT NULL
);

CREATE UNIQUE INDEX unq_code_system_cinm ON code_systems(code_system_name);

     * @param model
     */
    protected void addCodeSystems(PhysicalDataModel model) {

        codeSystemsTable = Table.builder(schemaName, CODE_SYSTEMS)
                .setTenantColumnName(MT_ID)
                .addIntColumn(      CODE_SYSTEM_ID,         false)
                .addVarcharColumn(CODE_SYSTEM_NAME,    255, false)
                .addUniqueIndex(IDX + "CODE_SYSTEM_CINM", CODE_SYSTEM_NAME)
                .addPrimaryKey(CODE_SYSTEMS + "_PK", CODE_SYSTEM_ID)
                .setTablespace(fhirTablespace)
                .addPrivileges(resourceTablePrivileges)
                .enableAccessControl(this.sessionVariable)
                .build(model);

        this.procedureDependencies.add(codeSystemsTable);
        model.addTable(codeSystemsTable);
        model.addObject(codeSystemsTable);

    }

    /**
     * @param pdm
     */
    public void buildProcedures(PhysicalDataModel pdm) {

        // Add a stored procedure for every resource type we have. We don't apply these procedures
        // until all the tables are done...just for simplicity.
        //for (String resourceType: this.resourceTypes) {
        //final String lcResourceName = resourceType.toLowerCase();
        //pdm.addProcedure(this.schemaName, lcResourceName + "_add_resource3", () -> this.readResourceTemplate(resourceType), Arrays.asList(allTablesComplete));
        //}
    }

    /**
     * Read the create procedure template which is made resource-type specific
     * @param resourceType
     * @return
     */
    protected String readResourceTemplate(String resourceType) {
        List<Replacer> replacers = new ArrayList<>();
        replacers.add(new Replacer("LC_RESOURCE_TYPE", resourceType.toLowerCase()));
        replacers.add(new Replacer("RESOURCE_TYPE", resourceType));

        return SchemaGeneratorUtil.readTemplate(this.adminSchemaName, this.schemaName, ADD_RESOURCE_TEMPLATE, replacers);
    }


    /**
     * Add the types we need for passing parameters to the stored procedures
       CREATE OR REPLACE TYPE <schema>.t_str_values AS ROW (parameter_name_id INTEGER, str_value VARCHAR(511 OCTETS), str_value_lcase   VARCHAR(511 OCTETS))
       CREATE OR REPLACE TYPE <schema>.t_str_values_arr AS <schema>.t_str_values ARRAY[256]
     */
    protected void addProcedureParameterTypes(PhysicalDataModel pdm) {
        // We get a deadlock: 'SQLCODE=-911, SQLSTATE=40001, SQLERRMC=2' if we try to create
        // these types in parallel, so we just create a dependency chain to serialize things
        IDatabaseObject dob;
        dob = addStrValuesTypes(pdm);
        dob = addTokenValuesTypes(pdm, dob);
        dob = addDateValuesTypes(pdm, dob);
        dob = addLatLngValuesTypes(pdm, dob);
        dob = addQuantityValuesTypes(pdm, dob);
        dob = addNumberValuesTypes(pdm, dob);
    }

    /**
     * Add the row and array types for str_values
     * @param pdm
     */
    protected IDatabaseObject addStrValuesTypes(PhysicalDataModel pdm) {

        // Add the row type first
        RowTypeBuilder strValuesBuilder = new RowTypeBuilder();
        strValuesBuilder
        .setSchemaName(this.schemaName)
        .setTypeName("t_str_values")
        .addBigIntColumn(PARAMETER_NAME_ID, false)
        .addVarcharColumn(STR_VALUE, 511, false)
        .addVarcharColumn(STR_VALUE_LCASE, 511, false);

        IDatabaseObject rt = strValuesBuilder.build();
        procedureDependencies.add(rt);
        pdm.addObject(rt);

        // Followed by the corresponding array type
        IDatabaseObject rat = new RowArrayType(schemaName, "t_str_values_arr", FhirSchemaConstants.INITIAL_VERSION, "t_str_values", ARRAY_SIZE);
        rat.addDependencies(Arrays.asList(rt));
        procedureDependencies.add(rat);
        pdm.addObject(rat);

        return rat;
    }

    /**
     *     
    t_token_values AS ROW ( parameter_name_id INTEGER, code_system_id    INTEGER, token_value       VARCHAR(255 OCTETS))';
    t_token_values_arr AS ' || CURRENT SCHEMA || '.t_token_values ARRAY[256]';


     * @param pdm
     */
    protected IDatabaseObject addTokenValuesTypes(PhysicalDataModel pdm, IDatabaseObject dob) {

        // Add the row type first
        RowTypeBuilder strValuesBuilder = new RowTypeBuilder();
        strValuesBuilder
        .setSchemaName(this.schemaName)
        .setTypeName("t_token_values")
        .addBigIntColumn(PARAMETER_NAME_ID, false)
        .addIntColumn(CODE_SYSTEM_ID, false)
        .addVarcharColumn(TOKEN_VALUE, 255, false);
        IDatabaseObject rt = strValuesBuilder.build();
        rt.addDependencies(Arrays.asList(dob));
        procedureDependencies.add(rt);
        pdm.addObject(rt);

        // Followed by the corresponding array type
        IDatabaseObject rat = new RowArrayType(schemaName, "t_token_values_arr", FhirSchemaConstants.INITIAL_VERSION, "t_token_values", ARRAY_SIZE);
        rat.addDependencies(Arrays.asList(rt));
        procedureDependencies.add(rat);
        pdm.addObject(rat);

        return rat;
    }

    /**
    t_date_values AS ROW ( parameter_name_id         INT, date_value          TIMESTAMP, date_start          TIMESTAMP, date_end            TIMESTAMP)';
    t_date_values_arr AS ' || CURRENT SCHEMA || '.t_date_values ARRAY[256]';
     * 
     * @param pdm
     */
    protected IDatabaseObject addDateValuesTypes(PhysicalDataModel pdm, IDatabaseObject dob) {

        // Add the row type first
        RowTypeBuilder strValuesBuilder = new RowTypeBuilder();
        strValuesBuilder
        .setSchemaName(this.schemaName)
        .setTypeName("t_date_values")
        .addBigIntColumn(PARAMETER_NAME_ID, false)
        .addTimestampColumn(DATE_VALUE, false)
        .addTimestampColumn(DATE_START, false)
        .addTimestampColumn(DATE_END, false);
        IDatabaseObject rt = strValuesBuilder.build();
        rt.addDependencies(Arrays.asList(dob));
        procedureDependencies.add(rt);
        pdm.addObject(rt);

        // Followed by the corresponding array type
        IDatabaseObject rat = new RowArrayType(schemaName, "t_date_values_arr", FhirSchemaConstants.INITIAL_VERSION, "t_date_values", ARRAY_SIZE);
        rat.addDependencies(Arrays.asList(rt));
        procedureDependencies.add(rat);
        pdm.addObject(rat);

        return rat;
    }

    /**
    t_number_values AS ROW ( parameter_name_id      INT, number_value        DOUBLE)';
    t_number_values_arr AS ' || CURRENT SCHEMA || '.t_number_values ARRAY[256]';

     * 
     * @param pdm
     */
    protected IDatabaseObject addNumberValuesTypes(PhysicalDataModel pdm, IDatabaseObject dob) {

        // Add the row type first
        RowTypeBuilder strValuesBuilder = new RowTypeBuilder();
        strValuesBuilder
        .setSchemaName(this.schemaName)
        .setTypeName("t_number_values")
        .addBigIntColumn(PARAMETER_NAME_ID, false)
        .addDoubleColumn(NUMBER_VALUE, false);
        IDatabaseObject rt = strValuesBuilder.build();
        rt.addDependencies(Arrays.asList(dob));
        procedureDependencies.add(rt);
        pdm.addObject(rt);

        // Followed by the corresponding array type
        IDatabaseObject rat = new RowArrayType(schemaName, "t_number_values_arr", FhirSchemaConstants.INITIAL_VERSION, "t_number_values", ARRAY_SIZE);
        rat.addDependencies(Arrays.asList(rt));
        procedureDependencies.add(rat);
        pdm.addObject(rat);

        return rat;
    }

    /**
    t_quantity_values AS ROW ( parameter_name_id        INT, code                 VARCHAR(255 OCTETS), quantity_value        DOUBLE, quantity_value_low    DOUBLE, quantity_value_high   DOUBLE, code_system_id           INT)';
    t_quantity_values_arr AS ' || CURRENT SCHEMA || '.t_quantity_values ARRAY[256]';


     * @param pdm
     */
    protected IDatabaseObject addQuantityValuesTypes(PhysicalDataModel pdm, IDatabaseObject dob) {

        // Add the row type first
        RowTypeBuilder strValuesBuilder = new RowTypeBuilder();
        strValuesBuilder
        .setSchemaName(this.schemaName)
        .setTypeName("t_quantity_values")
        .addBigIntColumn(PARAMETER_NAME_ID, false)
        .addVarcharColumn(CODE, 255, false)
        .addDoubleColumn(QUANTITY_VALUE, false)
        .addDoubleColumn(QUANTITY_VALUE_LOW, false)
        .addDoubleColumn(QUANTITY_VALUE_HIGH, false)
        .addIntColumn(CODE_SYSTEM_ID, false);
        IDatabaseObject rt = strValuesBuilder.build();
        rt.addDependencies(Arrays.asList(dob));
        procedureDependencies.add(rt);
        pdm.addObject(rt);

        // Followed by the corresponding array type
        IDatabaseObject rat = new RowArrayType(schemaName, "t_quantity_values_arr", FhirSchemaConstants.INITIAL_VERSION, "t_quantity_values", ARRAY_SIZE);
        rat.addDependencies(Arrays.asList(rt));
        procedureDependencies.add(rat);
        pdm.addObject(rat);

        return rat;
    }


    /**
    CREATE SEQUENCE fhir_sequence
             AS BIGINT
     START WITH 1
          CACHE 1000
       NO CYCLE;
     * 
     * @param pdm
     */
    protected void addFhirSequence(PhysicalDataModel pdm) {
        this.fhirSequence = new Sequence(schemaName, FHIR_SEQUENCE, FhirSchemaConstants.INITIAL_VERSION, 1000);
        procedureDependencies.add(fhirSequence);
        sequencePrivileges.forEach(p -> p.addToObject(fhirSequence));

        pdm.addObject(fhirSequence);
    }

    /**
     *     
    t_latlng_values AS ROW ( parameter_name_id      INT, latitude_value      DOUBLE, longitude_value     DOUBLE)';
    t_latlng_values_arr AS ' || CURRENT SCHEMA || '.t_latlng_values ARRAY[256]';

     * @param pdm
     */
    protected IDatabaseObject addLatLngValuesTypes(PhysicalDataModel pdm, IDatabaseObject dob) {

        // Add the row type first
        RowTypeBuilder strValuesBuilder = new RowTypeBuilder();
        strValuesBuilder
        .setSchemaName(this.schemaName)
        .setTypeName("t_latlng_values")
        .addBigIntColumn(PARAMETER_NAME_ID, false)
        .addDoubleColumn(LATITUDE_VALUE, false)
        .addDoubleColumn(LONGITUDE_VALUE, false);

        IDatabaseObject rt = strValuesBuilder.build();
        rt.addDependencies(Arrays.asList(dob));
        procedureDependencies.add(rt);
        pdm.addObject(rt);

        // Followed by the corresponding array type
        IDatabaseObject rat = new RowArrayType(schemaName, "t_latlng_values_arr", FhirSchemaConstants.INITIAL_VERSION, "t_latlng_values", ARRAY_SIZE);
        rat.addDependencies(Arrays.asList(rt));
        procedureDependencies.add(rat);
        pdm.addObject(rat);

        return rat;
    }

    /**
     * Visitor for the resource types
     */
    public void applyResourceTypes(Consumer<String> consumer) {
        for (String resourceType: this.resourceTypes) {
            consumer.accept(resourceType);
        }
    }

}