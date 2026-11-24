// SPDX-License-Identifier: Apache-2.0
package org.concordia.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.concordia.api.Interfaces.*;

@ApplicationScoped
public class PqsInterfaces implements InterfaceQueries {
    static final String SOURCE = "select contract_id, payload from active(?::text, ?::bigint) where payload_type = 'interface'";
    static final String DETAIL = SOURCE + " and contract_id = ?::text";
    static final String LIST = SOURCE + " and (?::text is null or contract_id collate \"C\" > ?::text collate \"C\") and (?::jsonb is null or payload -> 'resolution' = ?::jsonb) order by contract_id collate \"C\" limit ?";
    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    public static class Unavailable extends RuntimeException {
        public Unavailable(Exception cause) { super(cause); }
    }
    @Override public long latest() {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("select set_latest(latest_offset())"); var rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing PQS offset");
            long value = rows.getLong(1);
            if (rows.wasNull()) throw new SQLException("Missing PQS offset");
            return value;
        } catch (SQLException e) { throw new Unavailable(e); }
    }
    @Override public void validate(long snapshot) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("select validate_offset_exists(?::bigint)")) {
            statement.setLong(1, snapshot); statement.execute();
        } catch (SQLException e) { throw new Unavailable(e); }
    }
    @Override public Optional<Contract> find(Kind kind, long snapshot, String id) {
        return query(kind, snapshot, DETAIL, s -> s.setString(3,id)).stream().findFirst();
    }
    @Override public List<Contract> list(Kind kind, long snapshot, String after, int limit, String resolutionJson) {
        return query(kind, snapshot, LIST, s -> {
            s.setString(3,after); s.setString(4,after);
            s.setString(5,resolutionJson); s.setString(6,resolutionJson); s.setInt(7,limit);
        });
    }
    @FunctionalInterface interface Bind { void apply(PreparedStatement statement) throws SQLException; }
    private List<Contract> query(Kind kind, long snapshot, String sql, Bind bind) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1,kind.selector); statement.setLong(2,snapshot); bind.apply(statement);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<Contract>();
                while (rows.next()) result.add(new Contract(rows.getString("contract_id"), mapper.readValue(rows.getString("payload"),kind.viewType)));
                return result;
            }
        } catch (Exception e) { throw new Unavailable(e); }
    }
}
