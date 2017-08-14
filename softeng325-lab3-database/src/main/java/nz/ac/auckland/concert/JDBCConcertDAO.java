package nz.ac.auckland.concert;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.h2.tools.RunScript;
import org.joda.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nz.ac.auckland.concert.Genre;

/**
 * Implementation of the ConcertDAO interface. 
 * 
 * Concerts and Performers are persisted in relational tables. The ID fields of
 * Concert and Performer map to primary key columns of the Concert and 
 * Performer tables respectively.
 * 
 * @see ConcertDAO.
 *
 */
public class JDBCConcertDAO implements ConcertDAO {
	
	// H2 database configuration parameters.
	private static final String DATABASE_DRIVER_NAME = "org.h2.Driver";
	private static final String DATABASE_URL = "jdbc:h2:~/test;mv_store=false";
	private static final String DATABASE_USERNAME = "sa";
	private static final String DATABASE_PASSWORD = "sa";
	
	// Error messages.
	private static final String ERROR_CREATING_DAO = "Unable to create JDBCConcertDAO";
	private static final String ERROR_CLOSING_CONNECTION = "Unable to create JDBCConcertDAO";
	private static final String ERROR_SAVING_CONCERT = "Unable to save Concert";
	private static final String ERROR_DELETING_CONCERT = "Unable to save Concert";
	private static final String ERROR_LOADING_CONCERT = "Unable to retrieve Concert";
	private static final String ERROR_LOADING_ALL_CONCERTS = "Unable to retrieve all Concerts";
	
	// Column names for the Concert table.
	private static final String CONCERT_COLUMN_ID = "ID";
	private static final String CONCERT_COLUMN_TITLE = "TITLE";
	private static final String CONCERT_COLUMN_DATE = "DATE";
	private static final String CONCERT_COLUMN_PERFORMER_ID = "FK_PERFORMER_ID";
	
	// Column names for the Performer table.
	private static final String PERFORMER_COLUMN_NAME = "NAME";
	private static final String PERFORMER_COLUMN_S3IMAGE = "S3IMAGE";
	private static final String PERFORMER_COLUMN_GENRE = "GENRE";
	
	// SQL for inserting and updating the Performer table.
	private static final String SQL_INSERT_PERFORMER = "INSERT INTO PERFORMER VALUES (?,?,?,?)";
	private static final String SQL_UPDATE_PERFORMER = "UPDATE PERFORMER SET NAME = ?, S3IMAGE = ?, GENRE = ? WHERE ID = ?";
	
	// SQL for CRUD operations on the Concert table.
	private static final String SQL_INSERT_CONCERT = "INSERT INTO CONCERT VALUES (?,?,?,?)";
	private static final String SQL_UPDATE_CONCERT = "UPDATE CONCERT SET TITLE = ?, DATE = ?, FK_PERFORMER_ID = ? WHERE ID = ?";
	private static final String SQL_DELETE_CONCERT = "DELETE FROM CONCERT WHERE ID = ?";
	private static final String SQL_SELECT_CONCERT_BY_ID = "SELECT * FROM CONCERT WHERE ID = ?";
	private static final String SQL_SELECT_ALL_CONCERTS = "SELECT * FROM CONCERT";
	
	// SQL for finding largest primary keys values in the Concert and Performer tables.
	private static final String SQL_GET_LARGEST_PRIMARY_KEY_VALUE_FOR_CONCERT = "SELECT ID FROM CONCERT ORDER BY ID DESC LIMIT 1";
	private static final String SQL_GET_LARGEST_PRIMARY_KEY_VALUE_FOR_PERFORMER = "SELECT ID FROM PERFORMER ORDER BY ID DESC LIMIT 1";
	
	private static Logger _logger = LoggerFactory
			.getLogger(JDBCConcertDAO.class);

	// JDBC database connection.
	private Connection _jdbcConnection = null;
	
	/**
	 * Creates a JDBCConcertDAO. 
	 * 
	 * Following successful creation, the JDBCConcertDAO has established a 
	 * connection to the database.
	 * 
	 * @throws DAOException if there's an error connecting to the database.
	 */
	public JDBCConcertDAO() throws DAOException {
		try {
			// Load H2 database driver class.
			Class.forName(DATABASE_DRIVER_NAME);
				
			// Open a connection to the database.
			_jdbcConnection = DriverManager.getConnection(DATABASE_URL,
					DATABASE_USERNAME, DATABASE_PASSWORD);
		} catch(SQLException | ClassNotFoundException e) {
			_logger.debug(ERROR_CREATING_DAO, e);
			throw new DAOException(ERROR_CREATING_DAO);
		}
	}
	
	/**
	 * Creates a JDBCConcertDAO and runs a database initialisation script.
	 * 
	 * Following successful creation, the JDBCConcertDAO has established a 
	 * connection to the database.
	 * 
	 * @param scriptFile a text file containing database initialisation 
	 * instructions.
	 * 
	 * @throws DAOException if there's an error connecting to the database or
	 * running the script.
	 * 
	 */
	public JDBCConcertDAO(File scriptFile) throws DAOException {
		this();
		
		try {
			RunScript.execute(_jdbcConnection,  new FileReader(scriptFile));
		} catch(SQLException | FileNotFoundException e) {
			_logger.debug(ERROR_CREATING_DAO, e);
			throw new DAOException(ERROR_CREATING_DAO);
		}
	}
	
	/**
	 * @see ConcertDAO.close()
	 * 
	 */
	public void close() throws DAOException {
		try {
			_jdbcConnection.close();
		} catch(SQLException e) {
			_logger.debug(ERROR_CLOSING_CONNECTION, e);
			throw new DAOException(ERROR_CLOSING_CONNECTION);
		}
	}

	/**
	 * @see ConcertDAO.close()
	 */
	public void save(Concert concert) throws DAOException {
		try {
			Performer performer = concert.getPerformer();
			
			// Process the Concert's Performer first. It needs to be persisted 
			// in the database before inserting a new Concert because the
			// Concert table has a foreign key relationship with Performer.
			if(performer.getId() == null) {
				// Performer isn't stored in the database, so needs to be 
				// inserted. Generate the new primary key value and insert a
				// new Performer row.
				long key = getNextPrimaryKeyForPerformer();
				
				PreparedStatement preparedStatement = _jdbcConnection.prepareStatement(SQL_INSERT_PERFORMER);
				preparedStatement.setLong(1, key);
				preparedStatement.setString(2, performer.getName());
				preparedStatement.setString(3, performer.getS3ImageUri());
				preparedStatement.setString(4, performer.getGenre().toString());
				preparedStatement.executeUpdate();
				
				// Update the Performer's id instance variable.
				performer.setId(key);
			} else {
				// Performer is already persisted, so update its row in case 
				// the Performer object has been modified.
				PreparedStatement preparedStatement = _jdbcConnection.prepareStatement(SQL_UPDATE_PERFORMER);
				preparedStatement.setString(1, performer.getName());
				preparedStatement.setString(2, performer.getS3ImageUri());
				preparedStatement.setString(3, performer.getGenre().toString());
				preparedStatement.setLong(4, performer.getId());
				preparedStatement.executeUpdate();
			}
			
			// Process the Concert.
			if(concert.getId() == null) {
				// Concert isn't stored in the database, so needs to be 
				// inserted. Generate the new primary key value and insert a
				// new Concert row.
				long key = getNextPrimaryKeyForConcert();
				
				PreparedStatement preparedStatement = _jdbcConnection.prepareStatement(SQL_INSERT_CONCERT);
				preparedStatement.setLong(1, key);
				preparedStatement.setString(2, concert.getTitle());
				preparedStatement.setTimestamp(3, new Timestamp(concert.getDate().toDateTime().getMillis()));
				preparedStatement.setLong(4, concert.getPerformer().getId());
				preparedStatement.executeUpdate();
				
				// Update the Concert's id instance variable.
				concert.setId(key);
			} else {
				// Concert is already persisted, so update its row in case the
				// Concert object has been modified.
				PreparedStatement preparedStatement = _jdbcConnection.prepareStatement(SQL_UPDATE_CONCERT);
				preparedStatement.setString(1, concert.getTitle());
				preparedStatement.setTimestamp(2, new Timestamp(concert.getDate().toDateTime().getMillis()));
				preparedStatement.setLong(3, concert.getPerformer().getId());
				preparedStatement.setLong(4, concert.getId());
				preparedStatement.executeUpdate();
			}
		} catch(SQLException e) {
			_logger.debug(ERROR_SAVING_CONCERT, e);
			throw new DAOException(ERROR_SAVING_CONCERT);
		}
	}

	/**
	 * @see ConcertDAO.getById()
	 * 
	 */
	public Concert getById(long id) throws DAOException {
		try {
			PreparedStatement preparedStatement = _jdbcConnection.prepareStatement(SQL_SELECT_CONCERT_BY_ID);
			preparedStatement.setLong(1, id);
			ResultSet rs = preparedStatement.executeQuery();
			if (rs.next()){
				Long cid = rs.getLong(1);
				String title = rs.getString(2);
				LocalDateTime ldt = new org.joda.time.LocalDateTime(rs.getTimestamp(3).toLocalDateTime().toString());
				Long pid = rs.getLong(4);
				preparedStatement = _jdbcConnection.prepareStatement("SELECT * FROM PERFORMER WHERE ID = ?");
				preparedStatement.setLong(1, pid);
				ResultSet prs = preparedStatement.executeQuery();
				Performer p = null;
				if (prs.next()){
//					_logger.debug(prs.getString(1)+" what???");
//					_logger.debug(prs.getString(2)+" what???");
//					_logger.debug(prs.getString(3)+" what???");
//					_logger.debug(prs.getString(4)+" what???");
					p = new Performer(prs.getLong(1), prs.getString(2), prs.getString(3), Genre.valueOf(prs.getString(4)));
				}
				Concert concert = new Concert(cid, title, ldt, p);
				return concert;
			}else{
				return null;
			}
		} catch(SQLException e) {
			_logger.debug(ERROR_LOADING_CONCERT, e);
			throw new DAOException(ERROR_LOADING_CONCERT);
		}
	}

	/**
	 * @see ConcertDAO.getAll()
	 * 
	 */
	public List<Concert> getAll() throws DAOException {
		try {
			PreparedStatement preparedStatement = _jdbcConnection.prepareStatement(SQL_SELECT_ALL_CONCERTS);
			ResultSet rs = preparedStatement.executeQuery();
			List<Concert> concertList = new ArrayList<Concert>();
			List<Performer> performerList = new ArrayList<Performer>();
			while (rs.next()){
				Long cid = rs.getLong(1);
				String title = rs.getString(2);
				LocalDateTime ldt = new org.joda.time.LocalDateTime(rs.getTimestamp(3).toLocalDateTime().toString());
				Long pid = rs.getLong(4);
				preparedStatement = _jdbcConnection.prepareStatement("SELECT * FROM PERFORMER WHERE ID = ?");
				preparedStatement.setLong(1, pid);
				ResultSet prs = preparedStatement.executeQuery();
				Performer p = null;
				if (prs.next()){
					p = new Performer(prs.getLong(1), prs.getString(2), prs.getString(3), Genre.valueOf(prs.getString(4)));
					if (performerList.contains(p)){
						for (Performer pf : performerList){
							if (pf.equals(p)){
								p = pf;
								break;
							}
						}
					}else{
						performerList.add(p);
					}
				}
				Concert concert = new Concert(cid, title, ldt, p);
				concertList.add(concert);
//				_logger.debug(concert.getTitle()+" what???");
			}
			Collections.sort(concertList);
			return concertList;
		} catch(SQLException e) {
			_logger.debug(ERROR_LOADING_CONCERT, e);
			throw new DAOException(ERROR_LOADING_CONCERT);
		}
	}

	/**
	 * @see ConcertDAO.deleteConcert()
	 * 
	 */
	public void deleteConcert(Concert concert) throws DAOException {
		try {
			PreparedStatement statement = _jdbcConnection.prepareStatement(SQL_DELETE_CONCERT);
			statement.setLong(1,concert.getId());
			statement.executeUpdate();
		} catch(SQLException e) {
			_logger.debug(ERROR_DELETING_CONCERT, e);
			throw new DAOException(ERROR_DELETING_CONCERT);
		}
	}
	
	/*
	 * Helper method to generate the next primary key value for the Concert
	 * table.
	 * 
	 */
	private long getNextPrimaryKeyForConcert() throws SQLException {
		Statement statement = _jdbcConnection.createStatement();
		ResultSet rs = statement.executeQuery(SQL_GET_LARGEST_PRIMARY_KEY_VALUE_FOR_CONCERT);
		rs.next();
		long key = rs.getLong(1);
		
		return key + 1;
	}
	
	/*
	 * Helper method to generate the next primary key value for the Performer
	 * table.
	 * 
	 */
	private long getNextPrimaryKeyForPerformer() throws SQLException {
		Statement statement = _jdbcConnection.createStatement();
		ResultSet rs = statement.executeQuery(SQL_GET_LARGEST_PRIMARY_KEY_VALUE_FOR_PERFORMER);
		rs.next();
		long key = rs.getLong(1);
		
		return key + 1;
	}

}
