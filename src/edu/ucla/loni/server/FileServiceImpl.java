package edu.ucla.loni.server;

import java.io.File;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

import java.util.ArrayList;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.ucla.loni.client.FileService;
import edu.ucla.loni.shared.*;

import com.google.gwt.user.server.rpc.RemoteServiceServlet;

import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;


@SuppressWarnings("serial")
public class FileServiceImpl extends RemoteServiceServlet implements FileService {
	////////////////////////////////////////////////////////////
	// Private Variables
	////////////////////////////////////////////////////////////
	private Connection db_connection;
	private String db_name = "jdbc:hsqldb:hsql://localhost/xdb";
	private String db_username = "SA";
	private String db_password = "";
	
	////////////////////////////////////////////////////////////
	// Private Database Functions
	////////////////////////////////////////////////////////////
	/**
	 *  Returns a connection to the database
	 */
	private Connection getDatabaseConnection() throws Exception {
		if (db_connection == null){
			db_connection = DriverManager.getConnection(db_name, db_username, db_password);
		}
		
		return db_connection;
	}
	
	/**
	 *  Recursively get all pipefiles
	 */
	private ArrayList<File> getAllPipefiles(ArrayList<File> files, File dir){
	    if (dir.isDirectory()){
	    	for (File file : dir.listFiles()){
	    		getAllPipefiles(files, file);
	    	}
	    } else {
	    	String name = dir.getName();
	    	if (name.endsWith(".pipe")){
	    		files.add(dir);	
	    	}
	    }
	    
	    return files;
	}
	
	/** 
	 * Gets the directoryID of the root directory by selecting it from the database,
	 * inserts the directory into the database if needed
	 * 
	 * @param absolutePath absolute path of the root directory  
	 * @return directoryID of the root directory
	 */
	private int getDirectoryId(String absolutePath) throws Exception{
		int ret = selectDirectoryId(absolutePath);
		if(ret == -1){
			insertDirectoryId(absolutePath);
			ret = selectDirectoryId(absolutePath);
		}
		return ret;
	}
	
	/** 
	 * @param absolutePath absolute path of the root directory  
	 * @return directoryID of the root directory, or -1 if not found
	 */
	private int selectDirectoryId(String absolutePath) throws Exception{
		Connection con = getDatabaseConnection();
		
		PreparedStatement stmt = con.prepareStatement(
			"SELECT directoryID " +
			"FROM directory " +		
			"WHERE absolutePath = ?"		
		);
		stmt.setString(1, absolutePath);
		ResultSet rs = stmt.executeQuery();
		
		if (rs.next()){
			return rs.getInt(1);
		} else {
			return -1;
		}
	}
	
	
	/** 
	 * Inserts directory into the database, directoryID is automatically generated
	 * 
	 * @param absolutePath absolute path of the root directory  
	 */
	private void insertDirectoryId(String absolutePath) throws Exception{
		Connection con = getDatabaseConnection();
		
		PreparedStatement stmt = con.prepareStatement(
			"INSERT INTO directory (absolutePath) " +
			"VALUES (?)" 		
		);
		stmt.setString(1, absolutePath);
		stmt.executeUpdate();
	}
	
	/**
	 * ResultSet is from a query with the following form 
	 *   SELECT * FROM pipefile WHERE ...
	 */
	private Pipefile[] resultSetToPipefileArray(ResultSet rs) throws Exception{
		ArrayList<Pipefile> list = new ArrayList<Pipefile>();
		while (rs.next()) {
			Pipefile p = new Pipefile();
			
			// directoryID at index 1
			p.absolutePath = rs.getString(2);
			// lastModified at index 3
			p.name = rs.getString(4);
			p.type = rs.getString(5);
			p.packageName = rs.getString(6);
			p.description = rs.getString(7);
			p.tags = rs.getString(8);
			p.location = rs.getString(9);
			p.uri = rs.getString(10);
			
			p.access = rs.getString(11);
			
			list.add(p);
		}
		
		Pipefile[] ret = new Pipefile[list.size()];
		return list.toArray(ret);
	}
	
	/**
	 *  Update the database for this root folder 
	 *  @param root absolute path of the root directory
	 */
	private void updateDatabase(File rootDir) throws Exception {
		// Get all pipefiles recursively under this folder
		ArrayList<File> files = getAllPipefiles(new ArrayList<File>(), rootDir);
		
		if (files.size() > 0){
			Connection con = getDatabaseConnection();
			 
			int dirID = getDirectoryId(rootDir.getAbsolutePath());
			
			// For each pipefile
			for (File file : files){			    
			    // Get the lastModified of this pipefile to determine if database is up-to-date
			    PreparedStatement stmt = con.prepareStatement(
			    	"SELECT lastModified " +
					"FROM pipefile " +
					"WHERE absolutePath = ?" 		
				);
			    stmt.setString(1, file.getAbsolutePath());
				ResultSet rs = stmt.executeQuery();
			    
				// Determine if the row needs to be updated or inserted
			    boolean update = false;
			    boolean insert = true;
			    
			    Timestamp fs_lastModified = new Timestamp(file.lastModified());
				
			    if (rs.next()){
					insert = false;
					
					Timestamp db_lastModified = rs.getTimestamp(1);
					
					// If file has been modified
					if (db_lastModified.equals(fs_lastModified) == false){
						update = true;
					}
				}
				
				// If we need to update or insert a row
			    if (update || insert){			    	
			    	Pipefile pipe = ServerUtils.parseFile(file);
					
					if (insert){
						/*
						 * database schema for pipefile
						 */
						
						stmt = con.prepareStatement(
							"INSERT INTO pipefile (" +
								"directoryID, absolutePath, lastModified, " +
								"name, type, packageName, description, tags, " +
								"location, uri, access) " +
							"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
						);
						stmt.setInt(1, dirID);
						stmt.setString(2, pipe.absolutePath);
						stmt.setTimestamp(3, fs_lastModified);
						stmt.setString(4, pipe.name);
						stmt.setString(5, pipe.type);
						stmt.setString(6, pipe.packageName);
						stmt.setString(7, pipe.description);
						stmt.setString(8, pipe.tags);
						stmt.setString(9, pipe.location);
						stmt.setString(10, pipe.uri);
						stmt.setString(11, ""); // access
					} else {
						// directoryID and access are not based on the file in the system
						stmt = con.prepareStatement(
							"UPDATE pipefile " +
						    "SET name = ?, type = ?, packageName = ?, description = ?, tags = ?, " +
						    "location = ?, uri = ?, lastModified = ? " +
							"WHERE absolutePath = ?"
						);
						stmt.setString(1, pipe.name);
						stmt.setString(2, pipe.type);
						stmt.setString(3, pipe.packageName);
						stmt.setString(4, pipe.description);
						stmt.setString(5, pipe.tags);
						stmt.setString(6, pipe.location);
						stmt.setString(7, pipe.uri);
						stmt.setTimestamp(8, fs_lastModified);
						stmt.setString(9, pipe.absolutePath);
					}
					stmt.executeUpdate();
	 		    }
			}
		}
	}
	
	////////////////////////////////////////////////////////////
	// Public Functions
	////////////////////////////////////////////////////////////
	
	/**
	 *  Returns a FileTree that represents the root directory
	 *  <br>
	 *  Thus the children are the packages
	 *  @param root the absolute path of the root directory
	 */
	public Pipefile[] getFiles(String root) throws Exception {
		try {
			File rootDir = new File(root);
			if (rootDir.exists() && rootDir.isDirectory()){
				updateDatabase(rootDir);
				
				int dirID = getDirectoryId(root);
				
				Connection con = getDatabaseConnection();
				PreparedStatement stmt = con.prepareStatement(
			    	"SELECT * " +
					"FROM pipefile " +
					"WHERE directoryID = ?" 		
				);
			    stmt.setInt(1, dirID);
				ResultSet rs = stmt.executeQuery();
				
				return resultSetToPipefileArray(rs);
			} else {
				return null;
			}
		} 
		catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e.getMessage());
		}
	}
	
	/**
	 *  Returns a FileTree where the children are all files and are the search results
	 *  @param root the absolute path of the root directory
	 *  @param query what the user is searching for
	 */
	public Pipefile[] getSearchResults(String root, String query) throws Exception{
		try {
			// Convert to lower case so can test case insensitive
			query = query.toLowerCase();
			
			Connection con = getDatabaseConnection();
			int dirID = getDirectoryId(root);
			PreparedStatement stmt = con.prepareStatement(
				"SELECT * " +
				"FROM pipefile " +
				"WHERE directoryID = ? " +
					"AND (LCASE(name) LIKE '%" + query + "%' " +
					     "OR LCASE(packageName) LIKE '%" + query + "%'" +
					     "OR LCASE(description) LIKE '%" + query + "%'" +
					     "OR LCASE(tags) LIKE '%" + query + "%')" 
					//DO NOT CHANGE
					//for some reason on my computer making later setString substitution
					//was not producing the right result, i.e. not finding items in
					//database. My guess the setString was not formatting correctly.
					     
					// setString should now have single quotes surrounding it
					// Right now this code is subject to SQL injection, need to use setString
			);
			stmt.setInt(1, dirID);
			//stmt.setString(2, "'%" + query + "%'");
			ResultSet rs = stmt.executeQuery();
			
			return resultSetToPipefileArray(rs);
		} 
		catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e.getMessage());
		}
	}
	
	/**
	 *  Updates the file on the server
	 *  @param pipe Pipefile representing the updated file
	 */
	public void updateFile(Pipefile pipe){
		// TODO
		// Read the filename
		//   If the file exists
		//      read, parse the file, update the needed fields
		//      if the package changed update the name, move the file
		//      update the row corresponding to this file in the database
		//      rewrite the access file
		//   Else
		//      return
		File f = new File(pipe.absolutePath);
		if (!f.exists() || !f.canRead())
			return;
		Document doc;
		try
		{
			//parse
			doc = ServerUtils.parseXML(f);
		}
		catch(Exception e)
		{
			return;	//parseXML triggered exception
		}
		//TODO unknown pipe format
		//TODO rewrite the access file
	}
	
	/**
	 *  Removes a file from the server
	 *  @param filename absolute path of the file
	 */
	private void removeFile(String Filename) throws Exception {		
		File f = new File(Filename);
		if (f.exists()){
			f.delete(); // TODO: check return status
			Connection con = getDatabaseConnection();
			
			PreparedStatement stmt = con.prepareStatement(
				"DELETE FROM pipefile " +
				"WHERE absolutePath = ?" 		
			);
			stmt.setString(1, Filename);
			stmt.executeUpdate();
			//TODO: update access restrictions file
		}
	}
	
	/**
	 *  Removes files from the server
	 *  @param filenames absolute paths of the files
	 * @throws SQLException 
	 */
	public void removeFiles(String filenames[]) throws Exception {
		try {
			for (String filename : filenames) {
				removeFile(filename);
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e.getMessage());
		}
	}
	
	/**
	 *  Copy a file from the server to the proper package
	 *  @param filename absolute path of the file
	 *  @param packageName absolute path of the package
	 */
	private void copyFile(String filename, String packageName) throws Exception {
		// TODO
		// If the file exists
		//   Copy the file to the new destination
		//     File must be changed update the package
		//   Insert a row corresponding to this file in the database
		// do we insert or update? isnt this file already in db?
		return;	
	}
	
	/**
	 *  Copies files from the server to the proper package
	 *  @param filenames absolute paths of the files
	 *  @param packageName absolute path of the package
	 */
	public void copyFiles(String[] filenames, String packageName) throws Exception {
		// TODO
		// For each filename
		//   Call copyFile
		
		for (String filename : filenames) {
			copyFile(filename, packageName);
		}
	}
	
	/*
	*  given an absolute path string, this function attempts to isolate actual name of file
	*  @param s - absolute path of file
	*/
	protected String extractFileName(String s)
	{
		String res = "";
		for( int i = s.length() - 1; i >= 0; i-- )
		{
			if( s.charAt(i) == File.pathSeparatorChar )
			{
				res = s.substring(i + 1, s.length());
				break;
			}
		}
		return res;
	}
	
	/*
	*  given an absolute path string, this function isolates the directory absolute address where
	*  current file is placed
	*  @param s - absolute path of file
	*/
	protected String extractDirName(String s)
	{
		String res = "";
		for( int i = s.length() - 1; i >= 0; i-- )
		{
			if( s.charAt(i) == File.pathSeparatorChar )
			{
				res = s.substring(0, i);
				break;
			}
		}
		return res;
	}
	
	/**
	 *  Move a file from the server to the proper package
	 *  @param filename absolute path of the file = source path of file
	 *  @param packageName absolute path of the package = destination folder path
	 */
	//Need some clarification about packageName, right now I implemented it as destination folder abs path
	public void moveFile(String filename, String packageName){
		//find file in the database
		File source_file = new File(filename);
		//check that file exists
		if( source_file.exists() == false )
		{
			return; //file does not exist => abort
		}
		//get destination directory path
		File dir = new File(packageName);
		//compose package name, so that every ' ' (i.e. space char) be replaced with underscore char
		String formatted_package_name = extractFileName(packageName).replace(' ', '_');
		//move the file
		File dest_file = new File(dir, extractFileName(filename));
		boolean success = source_file.renameTo(dest_file);
		if( success == false )
		{
			return;	//for some reason file can not be moved => abort
		}
		//update XML
		Document doc;
		try
		{
			//parse
			doc = ServerUtils.parseXML(dest_file);
		}
		catch(Exception e)
		{
			return;	//parseXML triggered exception
		}
		//get list of nodes with tag_name = module
		NodeList nl = doc.getElementsByTagName("module");
		//loop thru all those nodes 
		for( int i = 0; i < nl.getLength(); i++ )
		{
			//get node item
			Node n = nl.item(i);
			NamedNodeMap attr = n.getAttributes();
			//get node's attribute = package
			Node nodeAttr = attr.getNamedItem("package");
			//set package value to formatted_package_name, which is currently dest_folder_path with white spaces replaced by underscore symbols
			nodeAttr.setTextContent(formatted_package_name);
		}
		try
		{
			//save changes made to the file
			//copied from <http://www.mkyong.com/java/how-to-modify-xml-file-in-java-dom-parser/>
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			DOMSource source = new DOMSource(doc);
			StreamResult result = new StreamResult(new File(dest_file.getAbsolutePath()));
			transformer.transform(source, result);
			//get connection
			Connection con = getDatabaseConnection();
			//find the source file in pipefile table
			PreparedStatement stmt = con.prepareStatement("SELECT * FROM pipefile WHERE absolutePath = ? AND directoryID = ?;");
			stmt.setString(1, filename);
			stmt.setInt(2, getDirectoryId(extractDirName(filename)));
			ResultSet rs = stmt.executeQuery();
			rs.next();
			String arg_name = rs.getString(3);
			String arg_type = rs.getString(4);
			String arg_desc = rs.getString(6);
			String arg_tags = rs.getString(7);
			String arg_access = rs.getString(8);
			String arg_seachable_text = rs.getString(11);
			//insert moved_file in pipefile table
			PreparedStatement stmt2 = con.prepareStatement("INSERT INTO pipefile VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);");
			stmt2.setInt(1, getDirectoryId(dir.getAbsolutePath()));
			stmt2.setString(2, dest_file.getAbsolutePath());
			stmt2.setString(3, arg_name);
			stmt2.setString(4, arg_type);
			stmt2.setString(5, formatted_package_name);
			stmt2.setString(6, arg_desc);
			stmt2.setString(7, arg_tags);
			stmt2.setString(8, arg_access);
			stmt2.setString(9, "");//TODO location ?? not sure what location means, in original XML overview it says "pipeline://localhost//bin/program"
			stmt2.setString(10, "");//TODO uri ?? same story with exception that URI does not even appear in XML template on XML_overview LONI web-page
			stmt2.setString(11, arg_seachable_text);
			stmt2.setTimestamp(12, new Timestamp(dest_file.lastModified()));
			stmt2.executeUpdate();
			//delete file from the pipefile table : [match file by absPath of file AND directoryID]
			PreparedStatement stmt3 = con.prepareStatement("DELETE FROM pipefile WHERE absolutePath = ? AND directoryID = ?;");
			stmt3.setString(1, filename);
			stmt3.setInt(2, getDirectoryId(extractDirName(filename)));
			stmt3.executeUpdate();
		}
		catch(Exception e)
		{
			//too bad... => abort
		}
		return;
	}
	
	/**
	 *  Moves files from the server to the proper package
	 *  @param filenames absolute paths of the files
	 *  @param packageName absolute path of the package
	 */
	public void moveFiles(String[] filenames, String packageName){
		for (String filename : filenames) {
			moveFile(filename, packageName);
		}
	}
	
	/**
	 *  Returns an array of all the groups
	 */
	public Group[] getGroups() throws Exception {
		// TODO
		// Call the database for a list of groups
		// Convert to proper format
		return null;
	}
	
	/**
	 *  Updates a group on the server (also used for creating groups)
	 *  @param group group to be updated
	 */
	public void	updateGroup(Group group) throws Exception{
		// TODO
		// Update the row in the database corresponding to this group
		return;
	}
}

