/*******************************************************************************
 * * Copyright 2012 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.mongodb;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.PersistenceException;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EmbeddableType;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.impetus.client.mongodb.utils.MongoDBUtils;
import com.impetus.kundera.db.RelationHolder;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.KunderaMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;
import com.impetus.kundera.metadata.model.type.AbstractManagedType;
import com.impetus.kundera.property.PropertyAccessException;
import com.impetus.kundera.property.PropertyAccessorHelper;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;

/**
 * Provides utility methods for handling data held in MongoDB.
 * 
 * @author amresh.singh
 */
public final class MongoDBDataHandler
{

    /** The log. */
    private static Logger log = LoggerFactory.getLogger(MongoDBDataHandler.class);

    /**
     * Gets the entity from document.
     * 
     * @param entityClass
     *            the entity class
     * @param m
     *            the m
     * @param document
     *            the document
     * @param relations
     *            the relations
     * @return the entity from document
     */
    public Map<String, Object> getEntityFromDocument(Class<?> entityClass, Object entity, EntityMetadata m,
            DBObject document, List<String> relations, Map<String, Object> relationValue)
    {
        // Entity object
        // Object entity = null;

        // Map to hold property-name=>foreign-entity relations
        try
        {
            // entity = entityClass.newInstance();

            // Populate primary key column
            Object rowKey = document.get("_id");
            Class<?> rowKeyValueClass = rowKey.getClass();
            Class<?> idClass = null;
            MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                    m.getPersistenceUnit());
            idClass = m.getIdAttribute().getJavaType();
            rowKey = MongoDBUtils.populateValue(rowKey, idClass);

            if (metaModel.isEmbeddable(m.getIdAttribute().getBindableJavaType()))
            {
                EmbeddableType embeddable = metaModel.embeddable(m.getIdAttribute().getBindableJavaType());
                Iterator<Attribute> iter = embeddable.getAttributes().iterator();
                Object compoundKey = m.getIdAttribute().getBindableJavaType().newInstance();
                while (iter.hasNext())
                {
                    AbstractAttribute compositeAttrib = (AbstractAttribute) iter.next();
                    Object value = ((BasicDBObject) rowKey).get(compositeAttrib.getJPAColumnName());
                    PropertyAccessorHelper.set(compoundKey, (Field) compositeAttrib.getJavaMember(), value);
                }
                PropertyAccessorHelper.setId(entity, m, compoundKey);
            }
            else
            {
                rowKey = MongoDBUtils.getTranslatedObject(rowKey, rowKeyValueClass, idClass);
                PropertyAccessorHelper.setId(entity, m, rowKey);
            }

            // Populate entity columns
            EntityType entityType = metaModel.entity(entityClass);

            Set<Attribute> columns = entityType.getAttributes();

            for (Attribute column : columns)
            {
                if (!column.equals(m.getIdAttribute()))
                {
                    String jpaColumnName = ((AbstractAttribute) column).getJPAColumnName();

                    Class javaType = ((AbstractAttribute) column).getBindableJavaType();
                    if (metaModel.isEmbeddable(javaType))
                    {
                        onViaEmbeddable(column, entity, metaModel, document);
                    }
                    else if (!column.isAssociation())
                    {
                        DocumentObjectMapper.setFieldValue(document, entity, column);
                    }
                    else if (relations != null)
                    {
                        if (relationValue == null)
                        {
                            relationValue = new HashMap<String, Object>();
                        }

                        if (relations.contains(jpaColumnName)
                                && !jpaColumnName.equals(((AbstractAttribute) m.getIdAttribute()).getJPAColumnName()))
                        {
                            Object colValue = document.get(jpaColumnName);
                            if (colValue != null)
                            {
                                String colFieldName = m.getFieldName(jpaColumnName);
                                Attribute attribute = colFieldName != null ? entityType.getAttribute(colFieldName)
                                        : null;
                                EntityMetadata relationMetadata = KunderaMetadataManager.getEntityMetadata(attribute
                                        .getJavaType());
                                colValue = MongoDBUtils.getTranslatedObject(colValue, colValue.getClass(),
                                        relationMetadata.getIdAttribute().getJavaType());
                            }
                            relationValue.put(jpaColumnName, colValue);
                        }
                    }
                }
            }
            return relationValue;
        }
        catch (InstantiationException e)
        {
            log.error("Error while instantiating " + entityClass + ", Caused by: ", e);
            return relationValue;
        }
        catch (IllegalAccessException e)
        {
            log.error("Error while Getting entity from Document, Caused by: ", e);
            return relationValue;
        }
        catch (PropertyAccessException e)
        {
            log.error("Error while Getting entity from Document, Caused by: ", e);
            return relationValue;
        }
    }

    /**
     * Gets the document from entity.
     * 
     * @param m
     *            the m
     * @param entity
     *            the entity
     * @param relations
     *            the relations
     * @return the document from entity
     * @throws PropertyAccessException
     *             the property access exception
     */
    Map<String, DBObject> getDocumentFromEntity(EntityMetadata m, Object entity, List<RelationHolder> relations)
            throws PropertyAccessException
    {
        Map<String, DBObject> dbObjects = new HashMap<String, DBObject>();

        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                m.getPersistenceUnit());
        EntityType entityType = metaModel.entity(m.getEntityClazz());

        // Populate Row Key
        Object id = PropertyAccessorHelper.getId(entity, m);

        DBObject dbObj = MongoDBUtils.getDBObject(m, m.getTableName(), dbObjects, metaModel, id);

        // dbObjects.put(m.getTableName(), dbObj);

        // Populate columns
        Set<Attribute> columns = entityType.getAttributes();
        for (Attribute column : columns)
        {
            String tableName = ((AbstractAttribute) column).getTableName() != null ? ((AbstractAttribute) column)
                    .getTableName() : m.getTableName();
            dbObj = MongoDBUtils.getDBObject(m, tableName, dbObjects, metaModel, id);

            if (!column.equals(m.getIdAttribute()))
            {
                try
                {
                    Class javaType = ((AbstractAttribute) column).getBindableJavaType();
                    if (metaModel.isEmbeddable(javaType))
                    {
                        Map<String, DBObject> embeddedObjects = onEmbeddable(column, entity, metaModel, dbObj,
                                m.getTableName());
                        for (String documentName : embeddedObjects.keySet())
                        {
                            DBObject db = dbObjects.get(documentName);
                            if (db == null)
                            {
                                db = MongoDBUtils.getDBObject(m, documentName, dbObjects, metaModel, id);
                            }
                            db.put(((AbstractAttribute) column).getJPAColumnName(), embeddedObjects.get(documentName));
                            dbObjects.put(documentName, db);

                        }
                    }
                    else if (!column.isAssociation())
                    {
                        DocumentObjectMapper.extractFieldValue(entity, dbObj, column);
                    }
                }
                catch (PropertyAccessException paex)
                {
                    log.error("Can't access property " + column.getName());
                }
            }
            // dbObjects.put(tableName, dbObj);
        }
        if (relations != null)
        {
            dbObj = dbObjects.get(m.getTableName());
            for (RelationHolder rh : relations)
            {
                dbObj.put(rh.getRelationName(),
                        MongoDBUtils.populateValue(rh.getRelationValue(), rh.getRelationValue().getClass()));
            }
            // dbObjects.put(m.getTableName(), dbObj);
        }

        if (((AbstractManagedType) entityType).isInherited())
        {
            dbObj = dbObjects.get(m.getTableName());
            String discrColumn = ((AbstractManagedType) entityType).getDiscriminatorColumn();
            String discrValue = ((AbstractManagedType) entityType).getDiscriminatorValue();

            // No need to check for empty or blank, as considering it as valid
            // name for nosql!
            if (discrColumn != null && discrValue != null)
            {
                dbObj.put(discrColumn, discrValue);
            }
            // dbObjects.put(m.getTableName(), dbObj);
        }
        return dbObjects;
    }

    /**
     * Retrieves A collection of embedded object within a document that match a
     * criteria specified in <code>query</code> TODO: This code requires a
     * serious overhawl. Currently it assumes that user query is in the form
     * "Select alias.columnName from EntityName alias". However, correct query
     * to be supported is
     * "Select alias.superColumnName.columnName from EntityName alias"
     * 
     * @param dbCollection
     *            the db collection
     * @param m
     *            the m
     * @param documentName
     *            the document name
     * @param mongoQuery
     *            the mongo query
     * @param result
     *            the result
     * @param orderBy
     *            the order by
     * @param maxResult
     * @return the embedded object list
     * @throws PropertyAccessException
     *             the property access exception
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    List getEmbeddedObjectList(DBCollection dbCollection, EntityMetadata m, String documentName,
            BasicDBObject mongoQuery, String result, BasicDBObject orderBy, int maxResult, BasicDBObject keys)
            throws PropertyAccessException, InstantiationException, IllegalAccessException
    {
        List list = new ArrayList();// List of embedded object to be returned

        // Specified after entity alias in query
        String columnName = result;

        // Something user didn't specify and we have to derive
        // TODO: User must specify this in query and remove this logic once
        // query format is changed

        String enclosingDocumentName = null;

        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                m.getPersistenceUnit());
        EntityType entityType = metaModel.entity(m.getEntityClazz());
        EmbeddableType superColumn = null;
        Set<Attribute> columns = null;
        Attribute attrib = null;
        try
        {
            attrib = entityType.getAttribute(columnName);
            Map<String, EmbeddableType> embeddables = metaModel.getEmbeddables(m.getEntityClazz());
            for (String key : embeddables.keySet())
            {
                superColumn = embeddables.get(key);
                columns = superColumn.getAttributes();

                for (Attribute column : columns)
                {
                    if (((AbstractAttribute) column).getJPAColumnName().equals(columnName))
                    {
                        enclosingDocumentName = key;
                        break;
                    }
                }
            }
        }
        catch (IllegalArgumentException iax)
        {
            if (log.isWarnEnabled())
            {
                log.warn("No column found for: " + columnName);
            }
        }

        // Query for fetching entities based on user specified criteria
        DBCursor cursor = orderBy != null ? dbCollection.find(mongoQuery, keys).sort(orderBy) : dbCollection.find(
                mongoQuery, keys).limit(maxResult);

        if (superColumn != null)
        {
            Field superColumnField = (Field) attrib.getJavaMember();
            while (cursor.hasNext())
            {
                DBObject fetchedDocument = cursor.next();
                Object embeddedDocumentObject = fetchedDocument.get(superColumnField.getName());

                if (embeddedDocumentObject != null)
                {
                    if (embeddedDocumentObject instanceof BasicDBList)
                    {
                        Class embeddedObjectClass = PropertyAccessorHelper.getGenericClass(superColumnField);
                        for (Object dbObj : (BasicDBList) embeddedDocumentObject)
                        {
                            Object obj = embeddedObjectClass.newInstance();
                            Object embeddedObject = new DocumentObjectMapper().getObjectFromDocument(metaModel,
                                    (BasicDBObject) dbObj, superColumn.getAttributes(), obj);
                            Object fieldValue = PropertyAccessorHelper.getObject(embeddedObject, columnName);
                        }
                    }
                    else if (embeddedDocumentObject instanceof BasicDBObject)
                    {
                        Object obj = superColumn.getJavaType().newInstance();
                        Object embeddedObject = DocumentObjectMapper.getObjectFromDocument(metaModel,
                                (BasicDBObject) embeddedDocumentObject, superColumn.getAttributes(), obj);
                        list.add(embeddedObject);
                    }
                    else
                    {
                        throw new PersistenceException("Can't retrieve embedded object from MONGODB document coz "
                                + "it wasn't stored as BasicDBObject, possible problem in format.");
                    }
                }
            }
        }
        return list;
    }

    /**
     * @param entityType
     * @param column
     * @param m
     * @param entity
     */
    Map<String, DBObject> onEmbeddable(Attribute column, Object entity, Metamodel metaModel, DBObject dbObj,
            String tableName)
    {
        EmbeddableType embeddableType = metaModel.embeddable(((AbstractAttribute) column).getBindableJavaType());
        Object embeddedObject = PropertyAccessorHelper.getObject(entity, (Field) column.getJavaMember());
        Map<String, DBObject> embeddedObjects = new HashMap<String, DBObject>();

        if (embeddedObject != null)
        {
            if (column.isCollection())
            {
                Collection embeddedCollection = (Collection) embeddedObject;
                // means it is case of element collection

                dbObj.put(((AbstractAttribute) column).getJPAColumnName(), DocumentObjectMapper
                        .getDocumentListFromCollection(metaModel, embeddedCollection, embeddableType.getAttributes(),
                                tableName));
            }
            else
            {
                embeddedObjects = DocumentObjectMapper.getDocumentFromObject(metaModel, embeddedObject,
                        embeddableType.getAttributes(), tableName);
                dbObj.put(((AbstractAttribute) column).getJPAColumnName(), embeddedObjects.get(tableName));
            }
        }
        return embeddedObjects;
    }

    /**
     * @param entityType
     * @param column
     * @param m
     * @param entity
     * @param embeddable
     * @param document
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    void onViaEmbeddable(Attribute column, Object entity, Metamodel metamodel, DBObject document)
            throws InstantiationException, IllegalAccessException
    {
        EmbeddableType embeddable = metamodel.embeddable(((AbstractAttribute) column).getBindableJavaType());
        Field embeddedField = (Field) column.getJavaMember();
        Object embeddedDocumentObject = null;

        if (column.isCollection())
        {
            Class embeddedObjectClass = PropertyAccessorHelper.getGenericClass(embeddedField);

            embeddedDocumentObject = document.get(((AbstractAttribute) column).getJPAColumnName());

            if (embeddedDocumentObject != null)
            {
                Collection embeddedCollection = DocumentObjectMapper.getCollectionFromDocumentList(metamodel,
                        (BasicDBList) embeddedDocumentObject, embeddedField.getType(), embeddedObjectClass,
                        embeddable.getAttributes());
                PropertyAccessorHelper.set(entity, embeddedField, embeddedCollection);
            }
        }
        else
        {
            Object obj = PropertyAccessorHelper.getObject(entity, (Field) column.getJavaMember());
            if (obj == null)
            {
                obj = ((AbstractAttribute) column).getBindableJavaType().newInstance();
            }
            embeddedDocumentObject = document.get(((AbstractAttribute) column).getJPAColumnName());
            PropertyAccessorHelper.set(entity, embeddedField, DocumentObjectMapper.getObjectFromDocument(metamodel,
                    (BasicDBObject) embeddedDocumentObject, embeddable.getAttributes(), obj));
        }
    }
}
