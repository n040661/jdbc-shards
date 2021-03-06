/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.suning.snfddal.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import javax.sql.DataSource;

import com.suning.snfddal.api.ErrorCode;
import com.suning.snfddal.dbobject.Comment;
import com.suning.snfddal.dbobject.DbObject;
import com.suning.snfddal.dbobject.Right;
import com.suning.snfddal.dbobject.Role;
import com.suning.snfddal.dbobject.Setting;
import com.suning.snfddal.dbobject.User;
import com.suning.snfddal.dbobject.UserAggregate;
import com.suning.snfddal.dbobject.UserDataType;
import com.suning.snfddal.dbobject.constraint.Constraint;
import com.suning.snfddal.dbobject.index.Index;
import com.suning.snfddal.dbobject.schema.Schema;
import com.suning.snfddal.dbobject.schema.SchemaObject;
import com.suning.snfddal.dbobject.table.Table;
import com.suning.snfddal.message.DbException;
import com.suning.snfddal.message.Trace;
import com.suning.snfddal.message.TraceSystem;
import com.suning.snfddal.route.RoutingHandler;
import com.suning.snfddal.route.RoutingHandlerImpl;
import com.suning.snfddal.util.BitField;
import com.suning.snfddal.util.New;
import com.suning.snfddal.util.SourceCompiler;
import com.suning.snfddal.value.CaseInsensitiveMap;
import com.suning.snfddal.value.CompareMode;
import com.suning.snfddal.value.Value;

/**
 * There is one database object per open database. The format of the meta data
 * table is: id int, 0, objectType int, sql varchar
 *
 * @since 2004-04-15 22:49
 */
public class Database {

    private final HashMap<String, Role> roles = New.hashMap();
    private final HashMap<String, User> users = New.hashMap();
    private final HashMap<String, Setting> settings = New.hashMap();
    private final HashMap<String, Schema> schemas = New.hashMap();
    private final HashMap<String, Right> rights = New.hashMap();
    private final HashMap<String, UserDataType> userDataTypes = New.hashMap();
    private final HashMap<String, UserAggregate> aggregates = New.hashMap();
    private final HashMap<String, Comment> comments = New.hashMap();
    private final HashMap<String, DataSource> dataNodeMapping = New.hashMap();

    private final Set<Session> userSessions = Collections.synchronizedSet(new HashSet<Session>());

    private final BitField objectIds = new BitField();
    private int nextSessionId;

    private TraceSystem traceSystem;
    private Trace trace;

    private CompareMode compareMode;
    private int allowLiterals = Constants.ALLOW_LITERALS_ALL;
    private volatile boolean closing;
    private boolean ignoreCase;
    private Mode mode = Mode.getInstance(Mode.MY_SQL);
    private int maxMemoryRows = SysProperties.MAX_MEMORY_ROWS;
    private int maxOperationMemory = Constants.DEFAULT_MAX_OPERATION_MEMORY;
    private final DbSettings dbSettings;

    private SourceCompiler compiler;
    private RoutingHandler routingHandler;

    public Database() {

        this.compareMode = CompareMode.getInstance(null, 0);
        this.dbSettings = DbSettings.getInstance(null);

        int traceLevelFile = TraceSystem.DEBUG;
        int traceLevelSystemOut = TraceSystem.DEBUG;

        traceSystem = new TraceSystem(null);
        traceSystem.setLevelFile(traceLevelFile);
        traceSystem.setLevelSystemOut(traceLevelSystemOut);
        trace = traceSystem.getTrace(Trace.DATABASE);

    }

    /**
     * Check if two values are equal with the current comparison mode.
     *
     * @param a the first value
     * @param b the second value
     * @return true if both objects are equal
     */
    public boolean areEqual(Value a, Value b) {
        // can not use equals because ValueDecimal 0.0 is not equal to 0.00.
        return a.compareTo(b, compareMode) == 0;
    }

    /**
     * Compare two values with the current comparison mode. The values may not
     * be of the same type.
     *
     * @param a the first value
     * @param b the second value
     * @return 0 if both values are equal, -1 if the first value is smaller, and
     *         1 otherwise
     */
    public int compare(Value a, Value b) {
        return a.compareTo(b, compareMode);
    }

    /**
     * Compare two values with the current comparison mode. The values must be
     * of the same type.
     *
     * @param a the first value
     * @param b the second value
     * @return 0 if both values are equal, -1 if the first value is smaller, and
     *         1 otherwise
     */
    public int compareTypeSave(Value a, Value b) {
        return a.compareTypeSave(b, compareMode);
    }

    /**
     * Get the trace object for the given module.
     *
     * @param module the module name
     * @return the trace object
     */
    public Trace getTrace(String module) {
        return traceSystem.getTrace(module);
    }

    @SuppressWarnings("unchecked")
    private HashMap<String, DbObject> getMap(int type) {
        HashMap<String, ? extends DbObject> result;
        switch (type) {
        case DbObject.USER:
            result = users;
            break;
        case DbObject.SETTING:
            result = settings;
            break;
        case DbObject.ROLE:
            result = roles;
            break;
        case DbObject.RIGHT:
            result = rights;
            break;
        case DbObject.SCHEMA:
            result = schemas;
            break;
        case DbObject.USER_DATATYPE:
            result = userDataTypes;
            break;
        case DbObject.COMMENT:
            result = comments;
            break;
        case DbObject.AGGREGATE:
            result = aggregates;
            break;
        default:
            throw DbException.throwInternalError("type=" + type);
        }
        return (HashMap<String, DbObject>) result;
    }

    /**
     * Add a schema object to the database.
     *
     * @param session the session
     * @param obj the object to add
     */
    public synchronized void addSchemaObject(SchemaObject obj) {
        obj.getSchema().add(obj);
        trace.debug("addSchemaObject: {0}", obj.getCreateSQL());
    }

    /**
     * Add an object to the database.
     *
     * @param session the session
     * @param obj the object to add
     */
    public synchronized void addDatabaseObject(DbObject obj) {
        HashMap<String, DbObject> map = getMap(obj.getType());
        String name = obj.getName();
        if (SysProperties.CHECK && map.get(name) != null) {
            DbException.throwInternalError("object already exists");
        }
        map.put(name, obj);
        trace.debug("addDatabaseObject: {0}", obj.getCreateSQL());
    }

    /**
     * Get the user defined aggregate function if it exists, or null if not.
     *
     * @param name the name of the user defined aggregate function
     * @return the aggregate function or null
     */
    public UserAggregate findAggregate(String name) {
        return aggregates.get(name);
    }

    /**
     * Get the comment for the given database object if one exists, or null if
     * not.
     *
     * @param object the database object
     * @return the comment or null
     */
    public Comment findComment(DbObject object) {
        if (object.getType() == DbObject.COMMENT) {
            return null;
        }
        String key = Comment.getKey(object);
        return comments.get(key);
    }

    /**
     * Get the role if it exists, or null if not.
     *
     * @param roleName the name of the role
     * @return the role or null
     */
    public Role findRole(String roleName) {
        return roles.get(roleName);
    }

    /**
     * Get the schema if it exists, or null if not.
     *
     * @param schemaName the name of the schema
     * @return the schema or null
     */
    public Schema findSchema(String schemaName) {
        Schema schema = schemas.get(schemaName);
        return schema;
    }

    /**
     * Get the setting if it exists, or null if not.
     *
     * @param name the name of the setting
     * @return the setting or null
     */
    public Setting findSetting(String name) {
        return settings.get(name);
    }

    /**
     * Get the user if it exists, or null if not.
     *
     * @param name the name of the user
     * @return the user or null
     */
    public User findUser(String name) {
        return users.get(name);
    }

    /**
     * Get the user defined data type if it exists, or null if not.
     *
     * @param name the name of the user defined data type
     * @return the user defined data type or null
     */
    public UserDataType findUserDataType(String name) {
        return userDataTypes.get(name);
    }

    /**
     * Get user with the given name. This method throws an exception if the user
     * does not exist.
     *
     * @param name the user name
     * @return the user
     * @throws DbException if the user does not exist
     */
    public User getUser(String name) {
        User user = findUser(name);
        if (user == null) {
            throw DbException.get(ErrorCode.USER_NOT_FOUND_1, name);
        }
        return user;
    }

    /**
     * Create a session for the given user.
     *
     * @param user the user
     * @return the session
     * @throws DbException if the database is in exclusive mode
     */
    public synchronized Session createSession(User user) {
        Session session = new Session(this, user, ++nextSessionId);
        userSessions.add(session);
        trace.info("connecting session #{0} to {1}", session.getId());
        return session;
    }

    /**
     * Remove a session. This method is called after the user has disconnected.
     *
     * @param session the session
     */
    public synchronized void removeSession(Session session) {
        if (session != null) {
            userSessions.remove(session);
        }
    }

    /**
     * Immediately close the database.
     */
    public void shutdownImmediately() {
        close();
    }

    /**
     * Close the database.
     *
     * @param fromShutdownHook true if this method is called from the shutdown
     *            hook
     */
    public synchronized void close() {
        if (closing) {
            return;
        }
        closing = true;
        if (userSessions.size() > 0) {
            Session[] all = new Session[userSessions.size()];
            userSessions.toArray(all);
            for (Session s : all) {
                try {
                    // must roll back, otherwise the session is removed and
                    // the transaction log that contains its uncommitted
                    // operations as well
                    s.rollback();
                    s.close();
                } catch (DbException e) {
                    trace.error(e, "disconnecting session #{0}", s.getId());
                }
            }
        }
        trace.info("Database closed");
        traceSystem.close();
    }

    /**
     * Allocate a new object id.
     *
     * @return the id
     */
    public synchronized int allocateObjectId() {
        int i = objectIds.nextClearBit(0);
        objectIds.set(i);
        return i;
    }

    public ArrayList<UserAggregate> getAllAggregates() {
        return New.arrayList(aggregates.values());
    }

    public ArrayList<Comment> getAllComments() {
        return New.arrayList(comments.values());
    }

    public int getAllowLiterals() {
        return allowLiterals;
    }

    public ArrayList<Right> getAllRights() {
        return New.arrayList(rights.values());
    }

    public ArrayList<Role> getAllRoles() {
        return New.arrayList(roles.values());
    }

    /**
     * Get all schema objects.
     *
     * @return all objects of all types
     */
    public ArrayList<SchemaObject> getAllSchemaObjects() {
        ArrayList<SchemaObject> list = New.arrayList();
        for (Schema schema : schemas.values()) {
            list.addAll(schema.getAll());
        }
        return list;
    }

    /**
     * Get all schema objects of the given type.
     *
     * @param type the object type
     * @return all objects of that type
     */
    public ArrayList<SchemaObject> getAllSchemaObjects(int type) {
        ArrayList<SchemaObject> list = New.arrayList();
        for (Schema schema : schemas.values()) {
            list.addAll(schema.getAll(type));
        }
        return list;
    }

    /**
     * Get all tables and views.
     *
     * @param includeMeta whether to force including the meta data tables (if
     *            true, metadata tables are always included; if false, metadata
     *            tables are only included if they are already initialized)
     * @return all objects of that type
     */
    public ArrayList<Table> getAllTablesAndViews() {
        ArrayList<Table> list = New.arrayList();
        for (Schema schema : schemas.values()) {
            list.addAll(schema.getAllTablesAndViews());
        }
        return list;
    }

    public ArrayList<Schema> getAllSchemas() {
        return New.arrayList(schemas.values());
    }

    public ArrayList<Setting> getAllSettings() {
        return New.arrayList(settings.values());
    }

    public ArrayList<UserDataType> getAllUserDataTypes() {
        return New.arrayList(userDataTypes.values());
    }

    public ArrayList<User> getAllUsers() {
        return New.arrayList(users.values());
    }

    public CompareMode getCompareMode() {
        return compareMode;
    }

    /**
     * Get all sessions that are currently connected to the database.
     *
     * @param includingSystemSession if the system session should also be
     *            included
     * @return the list of sessions
     */
    public Session[] getSessions() {
        ArrayList<Session> list;
        // need to synchronized on userSession, otherwise the list
        // may contain null elements
        synchronized (userSessions) {
            list = New.arrayList(userSessions);
        }
        Session[] array = new Session[list.size()];
        list.toArray(array);
        return array;
    }

    /**
     * Rename a schema object.
     *
     * @param session the session
     * @param obj the object
     * @param newName the new name
     */
    public synchronized void renameSchemaObject(Session session, SchemaObject obj, String newName) {
        obj.getSchema().rename(obj, newName);
    }

    /**
     * Rename a database object.
     *
     * @param session the session
     * @param obj the object
     * @param newName the new name
     */
    public synchronized void renameDatabaseObject(Session session, DbObject obj, String newName) {
        int type = obj.getType();
        HashMap<String, DbObject> map = getMap(type);
        if (SysProperties.CHECK) {
            if (!map.containsKey(obj.getName())) {
                DbException.throwInternalError("not found: " + obj.getName());
            }
            if (obj.getName().equals(newName) || map.containsKey(newName)) {
                DbException.throwInternalError("object already exists: " + newName);
            }
        }
        obj.checkRename();
        map.remove(obj.getName());
        obj.rename(newName);
        map.put(newName, obj);
    }

    /**
     * Get the schema. If the schema does not exist, an exception is thrown.
     *
     * @param schemaName the name of the schema
     * @return the schema
     * @throws DbException no schema with that name exists
     */
    public Schema getSchema(String schemaName) {
        Schema schema = findSchema(schemaName);
        if (schema == null) {
            throw DbException.get(ErrorCode.SCHEMA_NOT_FOUND_1, schemaName);
        }
        return schema;
    }

    /**
     * Remove the object from the database.
     *
     * @param session the session
     * @param obj the object to remove
     */
    public synchronized void removeDatabaseObject(Session session, DbObject obj) {
        String objName = obj.getName();
        int type = obj.getType();
        HashMap<String, DbObject> map = getMap(type);
        if (SysProperties.CHECK && !map.containsKey(objName)) {
            DbException.throwInternalError("not found: " + objName);
        }
        Comment comment = findComment(obj);
        if (comment != null) {
            removeDatabaseObject(session, comment);
        }
        obj.removeChildrenAndResources(session);
        map.remove(objName);
    }

    /**
     * Get the first table that depends on this object.
     *
     * @param obj the object to find
     * @param except the table to exclude (or null)
     * @return the first dependent table, or null
     */
    public Table getDependentTable(SchemaObject obj, Table except) {
        switch (obj.getType()) {
        case DbObject.COMMENT:
        case DbObject.CONSTRAINT:
        case DbObject.INDEX:
        case DbObject.RIGHT:
        case DbObject.TRIGGER:
        case DbObject.USER:
            return null;
        default:
        }
        HashSet<DbObject> set = New.hashSet();
        for (Table t : getAllTablesAndViews()) {
            if (except == t) {
                continue;
            } else if (Table.VIEW.equals(t.getTableType())) {
                continue;
            }
            set.clear();
            t.addDependencies(set);
            if (set.contains(obj)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Remove an object from the system table.
     *
     * @param session the session
     * @param obj the object to be removed
     */
    public synchronized void removeSchemaObject(Session session, SchemaObject obj) {
        int type = obj.getType();
        if (type == DbObject.TABLE_OR_VIEW) {
            Table table = (Table) obj;
            if (table.isTemporary() && !table.isGlobalTemporary()) {
                session.removeLocalTempTable(table);
                return;
            }
        } else if (type == DbObject.INDEX) {
            Index index = (Index) obj;
            Table table = index.getTable();
            if (table.isTemporary() && !table.isGlobalTemporary()) {
                session.removeLocalTempTableIndex(index);
                return;
            }
        } else if (type == DbObject.CONSTRAINT) {
            Constraint constraint = (Constraint) obj;
            Table table = constraint.getTable();
            if (table.isTemporary() && !table.isGlobalTemporary()) {
                session.removeLocalTempTableConstraint(constraint);
                return;
            }
        }
        Comment comment = findComment(obj);
        if (comment != null) {
            removeDatabaseObject(session, comment);
        }
        obj.getSchema().remove(obj);
    }

    public synchronized void addDataNode(String name, DataSource dataSource) {
        if (dataNodeMapping.containsKey(name)) {
            DbException.throwInternalError("data node already exists: " + name);
        }
        dataNodeMapping.put(name, dataSource);
    }

    public DataSource getDataNode(String name) {
        DataSource dataSource = dataNodeMapping.get(name);
        if (dataSource == null) {
            DbException.throwInternalError("data node not exists: " + name);
        }
        return dataSource;
    }

    public synchronized DataSource removeDataNode(String name) {
        DataSource dataSource = dataNodeMapping.get(name);
        if (dataSource == null) {
            DbException.throwInternalError("data node not found: " + name);
        }
        return dataNodeMapping.remove(name);
    }

    public TraceSystem getTraceSystem() {
        return traceSystem;
    }

    public void setCompareMode(CompareMode compareMode) {
        this.compareMode = compareMode;
    }

    /**
     * Commit the current transaction of the given session.
     *
     * @param session the session
     */
    synchronized void commit(Session session) {
        session.setAllCommitted();
    }

    /**
     * Check if the database is in the process of closing.
     *
     * @return true if the database is closing
     */
    public boolean isClosing() {
        return closing;
    }

    public void setIgnoreCase(boolean b) {
        ignoreCase = b;
    }

    public boolean getIgnoreCase() {
        return ignoreCase;
    }

    public void setAllowLiterals(int value) {
        this.allowLiterals = value;
    }

    public int getSessionCount() {
        return userSessions.size();
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMaxOperationMemory(int maxOperationMemory) {
        this.maxOperationMemory = maxOperationMemory;
    }

    public int getMaxOperationMemory() {
        return maxOperationMemory;
    }

    public int getMaxMemoryRows() {
        return maxMemoryRows;
    }

    public void setMaxMemoryRows(int value) {
        this.maxMemoryRows = value;
    }

    public DbSettings getSettings() {
        return dbSettings;
    }

    /**
     * Create a new hash map. Depending on the configuration, the key is case
     * sensitive or case insensitive.
     *
     * @param <V> the value type
     * @return the hash map
     */
    public <V> HashMap<String, V> newStringMap() {
        return dbSettings.databaseToUpper ? new HashMap<String, V>() : new CaseInsensitiveMap<V>();
    }

    /**
     * Compare two identifiers (table names, column names,...) and verify they
     * are equal. Case sensitivity depends on the configuration.
     *
     * @param a the first identifier
     * @param b the second identifier
     * @return true if they match
     */
    public boolean equalsIdentifiers(String a, String b) {
        if (a == b || a.equals(b)) {
            return true;
        }
        if (!dbSettings.databaseToUpper && a.equalsIgnoreCase(b)) {
            return true;
        }
        return false;
    }

    public SourceCompiler getCompiler() {
        if (compiler == null) {
            compiler = new SourceCompiler();
        }
        return compiler;
    }
    
    public RoutingHandler getRoutingHandler() {
        if (routingHandler == null) {
            routingHandler = new RoutingHandlerImpl(this);
        }
        return routingHandler;
    }
    
    

}
