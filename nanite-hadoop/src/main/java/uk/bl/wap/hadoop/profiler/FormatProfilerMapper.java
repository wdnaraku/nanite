package uk.bl.wap.hadoop.profiler;

/* 
 * For JobConf.get() property see:
 * http://hadoop.apache.org/common/docs/r0.18.3/mapred_tutorial.html
 */

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.log4j.Logger;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.archive.io.ArchiveRecordHeader;

import eu.scape_project.pc.cc.nanite.Nanite;

import uk.bl.wap.hadoop.WritableArchiveRecord;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResult;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResultCollection;

@SuppressWarnings( { "deprecation" } )
public class FormatProfilerMapper extends MapReduceBase implements Mapper<Text, WritableArchiveRecord, Text, Text> {
	private static Logger log = Logger.getLogger(FormatProfilerMapper.class.getName());
	String workingDirectory = "";
	Tika tika = new Tika();
	Nanite nanite = null;
	File tmpFile = null;

	public FormatProfilerMapper() {
		try {
			nanite = new Nanite();
			tmpFile = File.createTempFile("Nanite", "tmp");
		} catch ( Exception e) {
			e.printStackTrace();
			log.error("Exception on Nanite instanciation: "+e);
		}
	}

	@Override
	public void configure( JobConf job ) {
		this.workingDirectory = job.get( "mapred.work.output.dir" );
	}

	@Override
	public void map( Text key, WritableArchiveRecord value, OutputCollector<Text, Text> output, Reporter reporter ) throws IOException {
		ArchiveRecordHeader header = value.getRecord().getHeader();
		String serverType = "unknown";
		String tikaType = "unknown";
		String droidType = "unknown";
		String waybackYear = "unknown";

		// Get the ID:
		String wctID = this.getWctTi( key.toString() );
		
		// Get the server header data:
		if( !header.getHeaderFields().isEmpty() ) {
			// The crawl year:
			String waybackDate = ( ( String ) value.getRecord().getHeader().getDate() ).replaceAll( "[^0-9]", "" );
			if( waybackDate != null ) 
				waybackYear = waybackDate.substring(0,4);

			// Type according to server:
			serverType = value.getHttpHeader("Content-Type");
			if( serverType == null ) {
				output.collect( new Text("LOG: Server Content-Type is null."), new Text(wctID));
			}
		} else {
			output.collect( new Text("LOG: Empty header fields. "), new Text(key));
		}


		// Type according to Tiki:
		try {
			tikaType = tika.detect( value.getPayload() );
			// Now perform full parse:
			Metadata md = new Metadata();
			tika.parse( new ByteArrayInputStream( value.getPayload() ), md );
			//for( String name : md.names() ) {
			//}
			String tikaAppId = "";
			if( md.get( Metadata.APPLICATION_NAME ) != null ) tikaAppId += md.get( Metadata.APPLICATION_NAME );
			if( md.get( Metadata.APPLICATION_VERSION ) != null ) tikaAppId += "_"+md.get( Metadata.APPLICATION_VERSION);
			if( ! "".equals(tikaAppId) ) {
				tikaType = tikaType+"; appid=\""+tikaAppId+"\"";
			}

		} catch( Exception e ) {
			log.error( e.getMessage() );
			output.collect( new Text("LOG:ERROR Analysis threw exception: "+e+"\n"+getStackTrace(e)), new Text(key+" "+tmpFile+" "+value));
		}

		// Type according to Droid/Nanite:
		droidType = "application/octet-stream";
		try {
			IdentificationResultCollection irc = nanite.identify(
					Nanite.createByteArrayIdentificationRequest(tmpFile.toURI(), value.getPayload()));
			if( irc.getResults().size() > 0 ) {
				IdentificationResult res = irc.getResults().get(0);
				droidType = Nanite.getMimeTypeFromResult(res);
			} else {
				output.collect( new Text("LOG: Droid found no match."), new Text(wctID));
			}
		} catch( Exception e ) {
			e.printStackTrace();
			log.error("Exception on Nanite invocation: "+e);
			output.collect( new Text("LOG:ERROR Droid threw exception: "+e+"\n"+getStackTrace(e)), new Text(wctID) );
		}

		// Return the output for collation:
		output.collect( new Text( serverType+"\t"+tikaType+"\t"+droidType ), new Text( waybackYear ) );
	}
	
	private static String getStackTrace(Throwable aThrowable) {
	    final Writer result = new StringWriter();
	    final PrintWriter printWriter = new PrintWriter(result);
	    aThrowable.printStackTrace(printWriter);
	    return result.toString();
	  }

	private String getWctTi( String warcName ) {
		Pattern pattern = Pattern.compile( "^BL-\\b([0-9]+)\\b.*\\.warc(\\.gz)?$" );
		Matcher matcher = pattern.matcher( warcName );
		if( matcher.matches() ) {
			return matcher.group( 1 );
		}
		return "";
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#finalize()
	 */
	@Override
	protected void finalize() throws Throwable {
		super.finalize();
		if( tmpFile.exists() ) {
			tmpFile.delete();
		}
	}
	

	/**
	 * A simple test class to check the classpath is set up ok.
	 * 
	 * @param args
	 * @throws Exception 
	 * @throws IOException 
	 * @throws ConfigurationException 
	 */
	public static void  main( String[] args ) throws Exception {
		File file = new File(args[0]);
		Nanite nan = new Nanite();
		System.out.println("Nanite using binary sig. file version "+nan.getBinarySigFileVersion());
		nan.getMimeType(file);	
	}
}