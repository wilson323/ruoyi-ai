package org.ruoyi.workflow.workflow.checkpoint;

import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.sql.*;
import org.ruoyi.workflow.mapper.WorkflowCheckpointMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class WorkflowRunLeaseTest {
    @Test void independentSaversCannotAcquireSameRuntimeTwice() throws Exception {
        JdbcCheckpointSaver first=new JdbcCheckpointSaver(new FakeCheckpointDb().mapper()),second=new JdbcCheckpointSaver(new FakeCheckpointDb().mapper());
        try(var lease=first.acquireRun("lease-exclusive")) {
            lease.requireHeld();assertThrows(IllegalStateException.class,()->second.acquireRun("lease-exclusive"));
        }
        try(var next=second.acquireRun("lease-exclusive")) {next.requireHeld();}
    }
    @Test void databaseLockOwnershipLossIsDetectedBeforeEffects() throws Exception {
        DataSource ds=mock(DataSource.class);Connection connection=mock(Connection.class);when(ds.getConnection()).thenReturn(connection);
        PreparedStatement database=query(connection,"SELECT DATABASE()");ResultSet db=database.executeQuery();when(db.getString(1)).thenReturn("synthetic_db");
        PreparedStatement get=query(connection,"SELECT GET_LOCK(?, 0)");when(get.executeQuery().getInt(1)).thenReturn(1);
        PreparedStatement held=query(connection,"SELECT IS_USED_LOCK(?), CONNECTION_ID()");when(held.executeQuery().getLong(1)).thenReturn(7L);when(held.executeQuery().getLong(2)).thenReturn(8L);
        PreparedStatement release=query(connection,"SELECT RELEASE_LOCK(?)");when(release.executeQuery().getInt(1)).thenReturn(1);
        JdbcCheckpointSaver saver=new JdbcCheckpointSaver(mock(WorkflowCheckpointMapper.class),ds);
        try(var lease=saver.acquireRun("runtime")) {assertThrows(IllegalStateException.class,lease::requireHeld);}
        verify(connection).close();
    }
    @Test void busyDatabaseLockClosesConnectionAndFails() throws Exception {
        DataSource ds=mock(DataSource.class);Connection connection=mock(Connection.class);when(ds.getConnection()).thenReturn(connection);
        PreparedStatement database=query(connection,"SELECT DATABASE()");when(database.executeQuery().getString(1)).thenReturn("synthetic_db");
        query(connection,"SELECT GET_LOCK(?, 0)");
        assertThrows(IllegalStateException.class,()->new JdbcCheckpointSaver(mock(WorkflowCheckpointMapper.class),ds).acquireRun("runtime"));verify(connection).close();
    }
    private PreparedStatement query(Connection connection,String sql) throws Exception {
        PreparedStatement query=mock(PreparedStatement.class);ResultSet rows=mock(ResultSet.class);
        when(connection.prepareStatement(sql)).thenReturn(query);when(query.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true);return query;
    }
}
