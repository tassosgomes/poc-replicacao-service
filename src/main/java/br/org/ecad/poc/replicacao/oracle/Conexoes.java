package br.org.ecad.poc.replicacao.oracle;

import br.org.ecad.poc.replicacao.core.ReplicacaoException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Opens JDBC connections with manual commit: every adapter commits on purpose. */
public final class Conexoes {

    private Conexoes() {}

    public static Connection abrir(String url, String usuario, String senha) {
        try {
            Connection conexao = DriverManager.getConnection(url, usuario, senha);
            conexao.setAutoCommit(false);
            return conexao;
        } catch (SQLException e) {
            throw new ReplicacaoException("cannot connect to " + url, e);
        }
    }
}
