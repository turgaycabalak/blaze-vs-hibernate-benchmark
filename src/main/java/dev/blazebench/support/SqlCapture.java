package dev.blazebench.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Records the SQL Hibernate sends to the database (Blaze queries go through Hibernate too),
 * but only on the current thread and only inside {@link #capture}. Otherwise it is a no-op.
 */
public class SqlCapture implements StatementInspector {

	private static final ThreadLocal<List<String>> CAPTURED = new ThreadLocal<>();

	public static List<String> capture(Supplier<?> action) {
		List<String> statements = new ArrayList<>();
		CAPTURED.set(statements);
		try {
			action.get();
			return statements;
		}
		finally {
			CAPTURED.remove();
		}
	}

	@Override
	public String inspect(String sql) {
		List<String> statements = CAPTURED.get();
		if (statements != null) {
			statements.add(sql);
		}
		return sql;
	}

}
