// Copyright (c) 2008, EMC Corporation.
// Redistribution and use in source and binary forms, with or without modification, 
// are permitted provided that the following conditions are met:
//
//     + Redistributions of source code must retain the above copyright notice, 
//       this list of conditions and the following disclaimer.
//     + Redistributions in binary form must reproduce the above copyright 
//       notice, this list of conditions and the following disclaimer in the 
//       documentation and/or other materials provided with the distribution.
//     + The name of EMC Corporation may not be used to endorse or promote 
//       products derived from this software without specific prior written 
//       permission.
//
//      THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS 
//      "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED 
//      TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR 
//      PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS 
//      BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
//      CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
//      SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
//      INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
//      CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
//      ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
//      POSSIBILITY OF SUCH DAMAGE.
package com.emc.esu.api.rest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.message.AbstractHttpMessage;
import org.apache.log4j.Logger;
import org.jdom.Document;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import com.emc.esu.api.Acl;
import com.emc.esu.api.BufferSegment;
import com.emc.esu.api.Checksum;
import com.emc.esu.api.DirectoryEntry;
import com.emc.esu.api.EsuException;
import com.emc.esu.api.Extent;
import com.emc.esu.api.Grantee;
import com.emc.esu.api.Identifier;
import com.emc.esu.api.ListOptions;
import com.emc.esu.api.MetadataList;
import com.emc.esu.api.MetadataTag;
import com.emc.esu.api.MetadataTags;
import com.emc.esu.api.ObjectId;
import com.emc.esu.api.ObjectInfo;
import com.emc.esu.api.ObjectMetadata;
import com.emc.esu.api.ObjectPath;
import com.emc.esu.api.ObjectResult;
import com.emc.esu.api.ServiceInformation;

/**
 * This is an enhanced version of the REST API that uses the Apache Commons
 * HTTP Client as its transport layer instead of the built-in Java HTTP
 * client.  It should perform better at the expense of a slightly larger
 * footprint.  See the JARs in the commons-httpclient directory in the
 * project's root folder.
 * 
 * @author Jason Cwik
 *
 */
public class EsuRestApiApache extends AbstractEsuRestApi {
    private static final Logger l4j = Logger.getLogger( EsuRestApiApache.class );
    private DefaultHttpClient httpClient;

    /**
     * Creates a new EsuRestApiApache object.
     * 
     * @param host the hostname or IP address of the ESU server
     * @param port the port on the server to communicate with. Generally this is
     *            80 for HTTP and 443 for HTTPS.
     * @param uid the username to use when connecting to the server
     * @param sharedSecret the Base64 encoded shared secret to use to sign
     *            requests to the server.
     */
    public EsuRestApiApache(String host, int port, String uid, String sharedSecret) {
        super(host, port, uid, sharedSecret);
        SchemeRegistry schemeRegistry = new SchemeRegistry();
        
        if( port == 443 || (""+port).endsWith( "43" ) ) {
            schemeRegistry.register(
                    new Scheme("https", port, SSLSocketFactory.getSocketFactory()));
        } else {
            schemeRegistry.register(
                 new Scheme("http", port, PlainSocketFactory.getSocketFactory()));
        }

        ThreadSafeClientConnManager cm = new ThreadSafeClientConnManager(schemeRegistry);
        // Increase max total connection to 200
        cm.setMaxTotalConnections(200);
        // Increase default max connection per route to 20
        cm.setDefaultMaxPerRoute(200);

        httpClient = new DefaultHttpClient( cm, null );
    }

    /**
     * Creates a new object in the cloud.
     * 
     * @param acl Access control list for the new object. May be null to use a
     *            default ACL
     * @param metadata Metadata for the new object. May be null for no metadata.
     * @param data The initial contents of the object. May be appended to later.
     *            The stream will NOT be closed at the end of the request.
     * @param length The length of the stream in bytes. If the stream is longer
     *            than the length, only length bytes will be written. If the
     *            stream is shorter than the length, an error will occur.
     * @param mimeType the MIME type of the content. Optional, may be null. If
     *            data is non-null and mimeType is null, the MIME type will
     *            default to application/octet-stream.
     * @return Identifier of the newly created object.
     * @throws EsuException if the request fails.
     */
    public ObjectId createObjectFromStream(Acl acl, MetadataList metadata,
            InputStream data, long length, String mimeType) {
        try {
            String resource = context + "/objects";
            URL u = buildUrl(resource, null);

            if (data == null) {
                throw new IllegalArgumentException("Input stream is required");
            }

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            // Figure out the mimetype
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            headers.put("Content-Type", mimeType);
            headers.put("x-emc-uid", uid);

            // Process metadata
            if (metadata != null) {
                processMetadata(metadata, headers);
            }

            l4j.debug("meta " + headers.get("x-emc-meta"));

            // Add acl
            if (acl != null) {
                processAcl(acl, headers);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("POST", u, headers);

            HttpResponse response = restPost( u, headers, data, length );

            // Check response
            handleError( response );

            // The new object ID is returned in the location response header
            String location = response.getFirstHeader("location").getValue();
            
            // Cleanup the connection
            cleanup( response );

            // Parse the value out of the URL
            return getObjectId( location );
        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }
    
    /**
     * Creates a new object in the cloud using a BufferSegment.
     * 
     * @param acl Access control list for the new object. May be null to use a
     *            default ACL
     * @param metadata Metadata for the new object. May be null for no metadata.
     * @param data The initial contents of the object. May be appended to later.
     *            May be null to create an object with no content.
     * @param mimeType the MIME type of the content. Optional, may be null. If
     *            data is non-null and mimeType is null, the MIME type will
     *            default to application/octet-stream.
     * @param checksum if not null, use the Checksum object to compute
     * the checksum for the create object request.  If appending
     * to the object with subsequent requests, use the same
     * checksum object for each request.
     * @return Identifier of the newly created object.
     * @throws EsuException if the request fails.
     */
    public ObjectId createObjectFromSegment(Acl acl, MetadataList metadata,
            BufferSegment data, String mimeType, Checksum checksum) {

        try {
            String resource = context + "/objects";
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            // Figure out the mimetype
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            headers.put("Content-Type", mimeType);
            headers.put("x-emc-uid", uid);

            // Process metadata
            if (metadata != null) {
                processMetadata(metadata, headers);
            }

            l4j.debug("meta " + headers.get("x-emc-meta"));

            // Add acl
            if (acl != null) {
                processAcl(acl, headers);
            }

            // Process data
            if (data == null) {
                data = new BufferSegment(new byte[0]);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Compute checksum
            if( checksum != null ) {
            	checksum.update( data.getBuffer(), data.getOffset(), data.getSize() );
            	headers.put( "x-emc-wschecksum", checksum.toString() );
            }

            // Sign request
            signRequest("POST", u, headers);
            
            HttpResponse response = restPost( u, headers, data );


            // Check response
            handleError( response );

            // The new object ID is returned in the location response header
            String location = response.getFirstHeader("location").getValue();
            
            // Cleanup the connection
            cleanup( response );

            // Parse the value out of the URL
            return getObjectId( location );
        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Creates a new object in the cloud using a BufferSegment on the given
     * path.
     * 
     * @param path the path to create the object on.
     * @param acl Access control list for the new object. May be null to use a
     *            default ACL
     * @param metadata Metadata for the new object. May be null for no metadata.
     * @param data The initial contents of the object. May be appended to later.
     *            May be null to create an object with no content.
     * @param mimeType the MIME type of the content. Optional, may be null. If
     *            data is non-null and mimeType is null, the MIME type will
     *            default to application/octet-stream.
     * @param checksum if not null, use the Checksum object to compute
     * the checksum for the create object request.  If appending
     * to the object with subsequent requests, use the same
     * checksum object for each request.
     * @return the ObjectId of the newly-created object for references by ID.
     * @throws EsuException if the request fails.
     */
    public ObjectId createObjectFromSegmentOnPath(ObjectPath path, Acl acl,
            MetadataList metadata, BufferSegment data, String mimeType, Checksum checksum) {
        try {
            String resource = getResourcePath(context, path);
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            // Figure out the mimetype
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            headers.put("Content-Type", mimeType);
            headers.put("x-emc-uid", uid);

            // Process metadata
            if (metadata != null) {
                processMetadata(metadata, headers);
            }

            l4j.debug("meta " + headers.get("x-emc-meta"));

            // Add acl
            if (acl != null) {
                processAcl(acl, headers);
            }

            // Process data
            if (data == null) {
                data = new BufferSegment(new byte[0]);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Compute checksum
            if( checksum != null ) {
            	checksum.update( data.getBuffer(), data.getOffset(), data.getSize() );
            	headers.put( "x-emc-wschecksum", checksum.toString() );
            }

            // Sign request
            signRequest("POST", u, headers);
            HttpResponse response = restPost( u, headers, data );

            // Check response
            handleError( response );

            // The new object ID is returned in the location response header
            String location = response.getFirstHeader("location").getValue();
            
            // Cleanup the connection
            cleanup( response );

            // Parse the value out of the URL
            return getObjectId( location );

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Deletes an object from the cloud.
     * 
     * @param id the identifier of the object to delete.
     */
    public void deleteObject(Identifier id) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("DELETE", u, headers);
            
            HttpResponse response = restDelete( u, headers );
            
            handleError( response );
            
            cleanup( response );

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }
    
    /**
     * Deletes a version of an object from the cloud.
     * 
     * @param id the identifier of the object to delete.
     */
    public void deleteVersion(ObjectId id) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "versions");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("DELETE", u, headers);
            
            HttpResponse response = restDelete( u, headers );
            
            handleError( response );
            
            cleanup( response );

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Deletes metadata items from an object.
     * 
     * @param id the identifier of the object whose metadata to delete.
     * @param tags the list of metadata tags to delete.
     */
    public void deleteUserMetadata(Identifier id, MetadataTags tags) {
        if (tags == null) {
            throw new EsuException("Must specify tags to delete");
        }
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "metadata/user");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // process tags
            if (tags != null) {
                processTags(tags, headers);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("DELETE", u, headers);
            
            HttpResponse response = restDelete( u, headers );
            
            handleError( response );
            
            finishRequest( response );
            
        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Returns an object's ACL
     * 
     * @param id the identifier of the object whose ACL to read
     * @return the object's ACL
     */
    public Acl getAcl(Identifier id) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "acl");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);
            HttpResponse response = restGet( u, headers );
            
            handleError(response);

            // Parse return headers. User grants are in x-emc-useracl and
            // group grants are in x-emc-groupacl
            Acl acl = new Acl();
            readAcl(acl, response.getFirstHeader("x-emc-useracl").getValue(),
                    Grantee.GRANT_TYPE.USER);
            readAcl(acl, response.getFirstHeader("x-emc-groupacl").getValue(),
                    Grantee.GRANT_TYPE.GROUP);

            finishRequest( response );
            return acl;

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Returns a list of the tags that are listable the current user's tennant.
     * 
     * @param tag optional. If specified, the list will be limited to the tags
     *            under the specified tag. If null, only top level tags will be
     *            returned.
     * @return the list of listable tags.
     */
    public MetadataTags getListableTags(MetadataTag tag) {
        return getListableTags(tag == null ? null : tag.getName());
    }

    /**
     * Returns a list of the tags that are listable the current user's tennant.
     * 
     * @param tag optional. If specified, the list will be limited to the tags
     *            under the specified tag. If null, only top level tags will be
     *            returned.
     * @return the list of listable tags.
     */
    public MetadataTags getListableTags(String tag) {
        try {
            String resource = context + "/objects";
            URL u = buildUrl(resource, "listabletags");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add tag
            if (tag != null) {
                headers.put("x-emc-tags", tag);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);
            
            HttpResponse response = restGet( u, headers );
            handleError( response );

            String header = response.getFirstHeader("x-emc-listable-tags").getValue();
            l4j.debug("x-emc-listable-tags: " + header);
            MetadataTags tags = new MetadataTags();
            readTags(tags, header, true);

            finishRequest( response );
            return tags;
        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Fetches the system metadata for the object.
     * 
     * @param id the identifier of the object whose system metadata to fetch.
     * @param tags A list of system metadata tags to fetch. Optional. Default
     *            value is null to fetch all system metadata.
     * @return The list of system metadata for the object.
     */
    public MetadataList getSystemMetadata(Identifier id, MetadataTags tags) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "metadata/system");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // process tags
            if (tags != null) {
                processTags(tags, headers);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);

            HttpResponse response = restGet( u, headers );
            handleError( response );

            // Parse return headers. Regular metadata is in x-emc-meta and
            // listable metadata is in x-emc-listable-meta
            MetadataList meta = new MetadataList();
            readMetadata(meta, response.getFirstHeader("x-emc-meta"), false);
            readMetadata(meta, response.getFirstHeader("x-emc-listable-meta"), true);

            finishRequest( response );
            return meta;

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Fetches the user metadata for the object.
     * 
     * @param id the identifier of the object whose user metadata to fetch.
     * @param tags A list of user metadata tags to fetch. Optional. If null, all
     *            user metadata will be fetched.
     * @return The list of user metadata for the object.
     */
    public MetadataList getUserMetadata(Identifier id, MetadataTags tags) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "metadata/user");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // process tags
            if (tags != null) {
                processTags(tags, headers);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);

            HttpResponse response = restGet( u, headers );
            handleError( response );

            // Parse return headers. Regular metadata is in x-emc-meta and
            // listable metadata is in x-emc-listable-meta
            MetadataList meta = new MetadataList();
            readMetadata(meta, response.getFirstHeader("x-emc-meta"), false);
            readMetadata(meta, response.getFirstHeader("x-emc-listable-meta"), true);

            finishRequest( response );
            return meta;

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Lists all objects with the given tag.
     * 
     * @param tag the tag to search for
     * @return The list of objects with the given tag. If no objects are found
     *         the array will be empty.
     * @throws EsuException if no objects are found (code 1003)
     */
    public List<ObjectResult> listObjects(String tag, ListOptions options) {
        try {
            String resource = context + "/objects";
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add tag
            if (tag != null) {
                headers.put("x-emc-tags", tag);
            } else {
                throw new EsuException("Tag cannot be null");
            }
            
            // Process options
            if( options != null ) {
            	if( options.isIncludeMetadata() ) {
            		headers.put( "x-emc-include-meta", "1" );
            		if( options.getSystemMetadata() != null ) {
            			headers.put( "x-emc-system-tags", 
            					join( options.getSystemMetadata(), "," ) );
            		}
            		if( options.getUserMetadata() != null ) {
            			headers.put( "x-emc-user-tags", 
            					join( options.getUserMetadata(), "," ) );            			
            		}
            	}
            	if( options.getLimit() > 0 ) {
            		headers.put( "x-emc-limit", ""+options.getLimit() );
            	}
            	if( options.getToken() != null ) {
            		headers.put( "x-emc-token", options.getToken() );
            	}
            }


            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);
            
            HttpResponse response = restGet( u, headers );
            handleError( response );
            
            if( options != null ) {
            	// Update the token for listing more results.  If there are no
            	// more results, the header will not be set and the token will
            	// be cleared in the options object.
            	Header token = response.getFirstHeader("x-emc-token");
            	if( token != null ) {
            		options.setToken( token.getValue() );
            	} else {
            		options.setToken( null );
            	}
            } else {
            	Header token = response.getFirstHeader("x-emc-token");
            	if( token != null ) {
            		l4j.warn( "Result set truncated. Use ListOptions to " +
    				"retrieve token for next page of results." );
            	}            	
            }

            // Get object id list from response
            byte[] data = readStream( response.getEntity().getContent(), 
                    (int) response.getEntity().getContentLength() );

            if( l4j.isDebugEnabled() ) {
                l4j.debug("Response: " + new String(data, "UTF-8"));
            }
            finishRequest( response );

            return parseObjectListWithMetadata(data);

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Lists all objects with the given tag and returns both their IDs and their
     * metadata.
     * 
     * @param tag the tag to search for
     * @return The list of objects with the given tag. If no objects are found
     *         the array will be empty.
     * @throws EsuException if no objects are found (code 1003)
     */
    public List<ObjectResult> listObjectsWithMetadata(MetadataTag tag) {
        return listObjectsWithMetadata(tag.getName());
    }

    /**
     * Lists all objects with the given tag and returns both their IDs and their
     * metadata.
     * 
     * @param tag the tag to search for
     * @return The list of objects with the given tag. If no objects are found
     *         the array will be empty.
     * @throws EsuException if no objects are found (code 1003)
     */
    public List<ObjectResult> listObjectsWithMetadata(String tag) {
        try {
            String resource = context + "/objects";
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);
            headers.put("x-emc-include-meta", "1");

            // Add tag
            if (tag != null) {
                headers.put("x-emc-tags", tag);
            } else {
                throw new EsuException("Tag cannot be null");
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);

            HttpResponse response = restGet( u, headers );
            handleError( response );
            
            // Get object id list from response
            byte[] data = readStream( response.getEntity().getContent(), 
                    (int) response.getEntity().getContentLength() );

            if( l4j.isDebugEnabled() ) {
                l4j.debug("Response: " + new String(data, "UTF-8"));
            }
            finishRequest( response );

            return parseObjectListWithMetadata(data);

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Returns the list of user metadata tags assigned to the object.
     * 
     * @param id the object whose metadata tags to list
     * @return the list of user metadata tags assigned to the object
     */
    public MetadataTags listUserMetadataTags(Identifier id) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "metadata/tags");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);
            
            HttpResponse response = restGet( u, headers );
            handleError( response );

            // Get the user metadata tags out of x-emc-listable-tags and
            // x-emc-tags
            MetadataTags tags = new MetadataTags();

            readTags(tags, response.getFirstHeader("x-emc-listable-tags").getValue(), true);
            readTags(tags, response.getFirstHeader("x-emc-tags").getValue(), false);

            finishRequest( response );
            
            return tags;

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Lists the versions of an object.
     * 
     * @param id the object whose versions to list.
     * @return The list of versions of the object. If the object does not have
     *         any versions, the array will be empty.
     */
    public List<Identifier> listVersions(Identifier id) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "versions");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);

            HttpResponse response = restGet( u, headers );
            handleError( response );

            // Get object id list from response
            byte[] data = readStream( response.getEntity().getContent(), 
                    (int) response.getEntity().getContentLength() );

            if( l4j.isDebugEnabled() ) {
                l4j.debug("Response: " + new String(data, "UTF-8"));
            }
            finishRequest( response );

            return parseVersionList(data);

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Executes a query for objects matching the specified XQuery string.
     * 
     * @param xquery the XQuery string to execute against the cloud.
     * @return the list of objects matching the query. If no objects are found,
     *         the array will be empty.
     */
    public List<Identifier> queryObjects(String xquery) {
        try {
            String resource = context + "/objects";
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add query
            if (xquery != null) {
                headers.put("x-emc-xquery", xquery);
            } else {
                throw new EsuException("Query cannot be null");
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);
            
            HttpResponse response = restGet( u, headers );
            handleError( response );

            // Get object id list from response
            byte[] data = readStream( response.getEntity().getContent(), 
                    (int) response.getEntity().getContentLength() );

            if( l4j.isDebugEnabled() ) {
                l4j.debug("Response: " + new String(data, "UTF-8"));
            }
            finishRequest( response );
            return parseObjectList(data);

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }

    /**
     * Reads an object's content.
     * 
     * @param id the identifier of the object whose content to read.
     * @param extent the portion of the object data to read. Optional. Default
     *            is null to read the entire object.
     * @param buffer the buffer to use to read the extent. Must be large enough
     *            to read the response or an error will be thrown. If null, a
     *            buffer will be allocated to hold the response data. If you
     *            pass a buffer that is larger than the extent, only
     *            extent.getSize() bytes will be valid.
     * @param checksum if not null, the given checksum object will be used
     * to verify checksums during the read operation.  Note that only erasure
     * coded objects will return checksums *and* if you're reading the object
     * in chunks, you'll have to read the data back sequentially to keep
     * the checksum consistent.  If the read operation does not return
     * a checksum from the server, the checksum operation will be skipped.
     * @return the object data read as a byte array.
     */
    public byte[] readObject(Identifier id, Extent extent, byte[] buffer, Checksum checksum) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Add extent if needed
            if (extent != null && !extent.equals(Extent.ALL_CONTENT)) {
                headers.put(extent.getHeaderName(), extent.toString());
            }

            // Sign request
            signRequest("GET", u, headers);
            HttpResponse response = restGet( u, headers );

            // The requested content is in the response body.
            byte[] data = readStream( response.getEntity().getContent(), 
                    (int) response.getEntity().getContentLength() );

//            if( l4j.isDebugEnabled() ) {
//                l4j.debug("Response: " + new String(data, "UTF-8"));
//            }
            finishRequest( response );
            
            // See if a checksum was returned.
            Header checksumHeader = response.getFirstHeader("x-emc-wschecksum");
            
            if( checksumHeader != null && checksum != null ) {
            	String checksumStr = checksumHeader.getValue();
            	l4j.debug( "Checksum header: " + checksumStr );
            	checksum.setExpectedValue( checksumStr );
            	if( response.getEntity().getContentLength() != -1 ) {
            		checksum.update( data, 0, (int)response.getEntity().getContentLength() );
            	} else {
            		checksum.update( data, 0, data.length );
            	}
            }

            return data;

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }

    }

    /**
     * Reads an object's content and returns an InputStream to read the content.
     * Since the input stream is linked to the HTTP connection, it is imperative
     * that you close the input stream as soon as you are done with the stream
     * to release the underlying connection.
     * 
     * @param id the identifier of the object whose content to read.
     * @param extent the portion of the object data to read. Optional. Default
     *            is null to read the entire object.
     * @return an InputStream to read the object data.
     */
    public InputStream readObjectStream(Identifier id, Extent extent) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Add extent if needed
            if (extent != null && !extent.equals(Extent.ALL_CONTENT)) {
                headers.put(extent.getHeaderName(), extent.toString());
            }

            // Sign request
            signRequest("GET", u, headers);
            
            HttpResponse response = restGet( u, headers );
            handleError( response );

            return new CommonsInputStreamWrapper( response );

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }


    /**
     * Updates an object in the cloud and optionally its metadata and ACL.
     * 
     * @param id The ID of the object to update
     * @param acl Access control list for the new object. Optional, default is
     *            NULL to leave the ACL unchanged.
     * @param metadata Metadata list for the new object. Optional, default is
     *            NULL for no changes to the metadata.
     * @param data The new contents of the object. May be appended to later.
     *            Optional, default is NULL (no content changes).
     * @param extent portion of the object to update. May be null to indicate
     *            the whole object is to be replaced. If not null, the extent
     *            size must match the data size.
     * @param mimeType the MIME type of the content. Optional, may be null. If
     *            data is non-null and mimeType is null, the MIME type will
     *            default to application/octet-stream.
     * @param checksum if not null, use the Checksum object to compute
     * the checksum for the update object request.  If appending
     * to the object with subsequent requests, use the same
     * checksum object for each request.
     * @throws EsuException if the request fails.
     */
    public void updateObjectFromSegment(Identifier id, Acl acl,
            MetadataList metadata, Extent extent, BufferSegment data,
            String mimeType, Checksum checksum) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            // Figure out the mimetype
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            headers.put("Content-Type", mimeType);
            headers.put("x-emc-uid", uid);

            // Process metadata
            if (metadata != null) {
                processMetadata(metadata, headers);
            }

            l4j.debug("meta " + headers.get("x-emc-meta"));

            // Add acl
            if (acl != null) {
                processAcl(acl, headers);
            }

            // Add extent if needed
            if (extent != null && !extent.equals(Extent.ALL_CONTENT)) {
                headers.put(extent.getHeaderName(), extent.toString());
            }

            // Process data
            if (data == null) {
                data = new BufferSegment(new byte[0]);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Compute checksum
            if( checksum != null ) {
            	checksum.update( data.getBuffer(), data.getOffset(), data.getSize() );
            	headers.put( "x-emc-wschecksum", checksum.toString() );
            }

            // Sign request
            signRequest("PUT", u, headers);
            
            HttpResponse response = restPut( u, headers, data );
            handleError( response );
            
            finishRequest( response );
        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }

    }

    /**
     * Updates an object in the cloud.
     * 
     * @param id The ID of the object to update
     * @param acl Access control list for the new object. Optional, default is
     *            NULL to leave the ACL unchanged.
     * @param metadata Metadata list for the new object. Optional, default is
     *            NULL for no changes to the metadata.
     * @param data The updated data to apply to the object. Requred. Note that
     *            the input stream is NOT closed at the end of the request.
     * @param extent portion of the object to update. May be null to indicate
     *            the whole object is to be replaced. If not null, the extent
     *            size must match the data size.
     * @param length The length of the stream in bytes. If the stream is longer
     *            than the length, only length bytes will be written. If the
     *            stream is shorter than the length, an error will occur.
     * @param mimeType the MIME type of the content. Optional, may be null. If
     *            data is non-null and mimeType is null, the MIME type will
     *            default to application/octet-stream.
     * @throws EsuException if the request fails.
     */
    public void updateObjectFromStream(Identifier id, Acl acl,
            MetadataList metadata, Extent extent, InputStream data, long length,
            String mimeType) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            // Figure out the mimetype
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            headers.put("Content-Type", mimeType);
            headers.put("x-emc-uid", uid);

            // Process metadata
            if (metadata != null) {
                processMetadata(metadata, headers);
            }

            l4j.debug("meta " + headers.get("x-emc-meta"));

            // Add acl
            if (acl != null) {
                processAcl(acl, headers);
            }

            // Add extent if needed
            if (extent != null && !extent.equals(Extent.ALL_CONTENT)) {
                headers.put(extent.getHeaderName(), extent.toString());
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("PUT", u, headers);
            
            HttpResponse response = restPut( u, headers, data, length );
            handleError( response );
            
            finishRequest( response );
            
        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }

    }

    /**
     * Writes the metadata into the object. If the tag does not exist, it is
     * created and set to the corresponding value. If the tag exists, the
     * existing value is replaced.
     * 
     * @param id the identifier of the object to update
     * @param metadata metadata to write to the object.
     */
    public void setUserMetadata(Identifier id, MetadataList metadata) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "metadata/user");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Process metadata
            if (metadata != null) {
                processMetadata(metadata, headers);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("POST", u, headers);
            
            HttpResponse response = restPost( u, headers, null );
            handleError( response );
            
            finishRequest( response );
        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }

    }

    /**
     * Sets (overwrites) the ACL on the object.
     * 
     * @param id the identifier of the object to change the ACL on.
     * @param acl the new ACL for the object.
     */
    public void setAcl(Identifier id, Acl acl) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "acl");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add acl
            if (acl != null) {
                processAcl(acl, headers);
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("POST", u, headers);
            
            HttpResponse response = restPost( u, headers, null );
            handleError( response );
            
            finishRequest( response );
            
        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }

    }

    /**
     * Creates a new immutable version of an object.
     * 
     * @param id the object to version
     * @return the id of the newly created version
     */
    public ObjectId versionObject(Identifier id) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "versions");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("POST", u, headers);
            
            HttpResponse response = restPost( u, headers, null );
            handleError( response );
            
            // The new object ID is returned in the location response header
            String location = response.getFirstHeader("location").getValue();
            
            finishRequest( response );

            // Parse the value out of the URL
            return getObjectId( location );

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }


    /**
     * Lists the contents of a directory.
     * 
     * @param path the path to list. Must be a directory.
     * @return the directory entries in the directory.
     */
	public List<DirectoryEntry> listDirectory(ObjectPath path, 
			ListOptions options) {
    	
        if (!path.isDirectory()) {
            throw new EsuException(
                    "listDirectory must be called with a directory path");
        }

        // Read out the directory's contents
        byte[] data = null;
        try {
            String resource = getResourcePath(context, path);
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Process options
            if( options != null ) {
            	if( options.isIncludeMetadata() ) {
            		headers.put( "x-emc-include-meta", "1" );
            		if( options.getSystemMetadata() != null ) {
            			headers.put( "x-emc-system-tags", 
            					join( options.getSystemMetadata(), "," ) );
            		}
            		if( options.getUserMetadata() != null ) {
            			headers.put( "x-emc-user-tags", 
            					join( options.getUserMetadata(), "," ) );            			
            		}
            	}
            	if( options.getLimit() > 0 ) {
            		headers.put( "x-emc-limit", ""+options.getLimit() );
            	}
            	if( options.getToken() != null ) {
            		headers.put( "x-emc-token", options.getToken() );
            	}
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);
            HttpResponse response = restGet( u, headers );

            if( options != null ) {
            	// Update the token for listing more results.  If there are no
            	// more results, the header will not be set and the token will
            	// be cleared in the options object.
            	Header token = response.getFirstHeader("x-emc-token");
            	if( token != null ) {
            		options.setToken( token.getValue() );
            	} else {
            		options.setToken( null );
            	}
            } else {
            	Header token = response.getFirstHeader("x-emc-token");
            	if( token != null ) {
            		l4j.warn( "Result set truncated. Use ListOptions to " +
    				"retrieve token for next page of results." );
            	}            	
            }

            // The requested content is in the response body.
            data = readStream( response.getEntity().getContent(), 
                    (int) response.getEntity().getContentLength() );

            finishRequest( response );
            
        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }

 
        return parseDirectoryListing( data, path );

    }

    public ObjectMetadata getAllMetadata(Identifier id) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("HEAD", u, headers);
            
            HttpResponse response = restHead( u, headers );
            handleError( response );
 
            // Parse return headers. User grants are in x-emc-useracl and
            // group grants are in x-emc-groupacl
            Acl acl = new Acl();
            readAcl(acl, response.getFirstHeader("x-emc-useracl").getValue(),
                    Grantee.GRANT_TYPE.USER);
            readAcl(acl, response.getFirstHeader("x-emc-groupacl").getValue(),
                    Grantee.GRANT_TYPE.GROUP);

            // Parse return headers. Regular metadata is in x-emc-meta and
            // listable metadata is in x-emc-listable-meta
            MetadataList meta = new MetadataList();
            readMetadata(meta, response.getFirstHeader("x-emc-meta"), false);
            readMetadata(meta, response.getFirstHeader("x-emc-listable-meta"), true);

            ObjectMetadata om = new ObjectMetadata();
            om.setAcl(acl);
            om.setMetadata(meta);
            om.setMimeType(response.getFirstHeader( "Content-Type").getValue());

            finishRequest( response );
            
            return om;

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    }
    
	@Override
	public ServiceInformation getServiceInformation() {
        try {
            String resource = context + "/service";
            URL u = buildUrl(resource, null);

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);
            
            HttpResponse response = restGet( u, headers );
            handleError( response );

            // Get object id list from response
            byte[] data = readStream( response.getEntity().getContent(), 
                    (int) response.getEntity().getContentLength() );

            if( l4j.isDebugEnabled() ) {
                l4j.debug("Response: " + new String(data, "UTF-8"));
            }
            finishRequest( response );

            return parseServiceInformation(data);

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
	}
	
	
    /**
     * Renames a file or directory within the namespace.
     * @param source The file or directory to rename
     * @param destination The new path for the file or directory
     * @param force If true, the desination file or 
     * directory will be overwritten.  Directories must be empty to be 
     * overwritten.  Also note that overwrite operations on files are
     * not synchronous; a delay may be required before the object is
     * available at its destination.
     */
    public void rename(ObjectPath source, ObjectPath destination, boolean force) {
        try {
            String resource = getResourcePath(context, source);
            URL u = buildUrl(resource, "rename");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            String destPath = destination.toString();
            if (destPath.startsWith("/"))
            {
                destPath = destPath.substring(1);
            }
            headers.put("x-emc-path", destPath);

            if (force) {
                headers.put("x-emc-force", "true");
            }

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("POST", u, headers);
            
            HttpResponse response = restPost( u, headers, null );
            handleError( response );
            
            finishRequest( response );

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    	
    }

    /**
     * Restores a version of an object to the base version (i.e. "promote" an 
     * old version to the current version).
     * @param id Base object ID (target of the restore)
     * @param vId Version object ID to restore
     */
    public void restoreVersion( ObjectId id, ObjectId vId ) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "versions");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);
            
            // Version to promote
            headers.put("x-emc-version-oid", vId.toString());

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("PUT", u, headers);
            
            HttpResponse response = restPut( u, headers, null );
            
            handleError( response );
            
            cleanup( response );

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    	
    }
    
    /**
     * Get information about an object's state including
     * replicas, expiration, and retention.
     * @param id the object identifier
     * @return and ObjectInfo object containing the state information
     */
    public ObjectInfo getObjectInfo( Identifier id ) {
        try {
            String resource = getResourcePath(context, id);
            URL u = buildUrl(resource, "info");

            // Build headers
            Map<String, String> headers = new HashMap<String, String>();

            headers.put("x-emc-uid", uid);

            // Add date
            headers.put("Date", getDateHeader());

            // Sign request
            signRequest("GET", u, headers);

            HttpResponse response = restGet( u, headers );
            handleError( response );

            // Get object id list from response
            byte[] data = readStream( response.getEntity().getContent(), 
                    (int) response.getEntity().getContentLength() );

            String responseXml = new String(data, "UTF-8");
            if( l4j.isDebugEnabled() ) {
                l4j.debug("Response: " + responseXml);
            }
            finishRequest( response );

            return new ObjectInfo(responseXml);

        } catch (MalformedURLException e) {
            throw new EsuException("Invalid URL", e);
        } catch (IOException e) {
            throw new EsuException("Error connecting to server", e);
        } catch (GeneralSecurityException e) {
            throw new EsuException("Error computing request signature", e);
        } catch (URISyntaxException e) {
            throw new EsuException("Invalid URL", e);
        }
    	
    }


    
    
    /////////////////////
    // Private Methods //
    /////////////////////
    
    private void handleError(HttpResponse resp) {
        StatusLine status = resp.getStatusLine();
        if( status.getStatusCode() > 299 ) {
            try {
                HttpEntity body = resp.getEntity();
                if( body == null ) {
                    throw new EsuException( status.getReasonPhrase(), status.getStatusCode() );
                }
                
                byte[] response = readStream( body.getContent(), (int) body.getContentLength() );
                l4j.debug("Error response: " + new String(response, "UTF-8"));
                SAXBuilder sb = new SAXBuilder();

                Document d = sb.build(new ByteArrayInputStream(response));

                String code = d.getRootElement().getChildText("Code");
                String message = d.getRootElement().getChildText("Message");

                if (code == null && message == null) {
                    // not an error from ESU
                    throw new EsuException( status.getReasonPhrase(), status.getStatusCode() );
                }

                l4j.debug("Error: " + code + " message: " + message);
                throw new EsuException(message, status.getStatusCode(), Integer.parseInt(code));

            } catch (IOException e) {
                l4j.debug("Could not read error response body", e);
                // Just throw what we know from the response
                throw new EsuException(status.getReasonPhrase(), status.getStatusCode());
            } catch (JDOMException e) {
                l4j.debug("Could not parse response body for " + status.getStatusCode()
                        + ": " + status.getReasonPhrase(), e);
                throw new EsuException("Could not parse response body for "
                        + status.getStatusCode() + ": " + status.getReasonPhrase(), e,
                        status.getStatusCode());

            } finally {
                if( resp.getEntity() != null ) {
                    try {
                        resp.getEntity().consumeContent();
                    } catch (IOException e) {
                        l4j.warn( "Error finishing error response", e );
                    }
                }
            }

        }
    }

    private HttpResponse restPost( URL url, Map<String,String> headers, InputStream in, long contentLength ) throws URISyntaxException, ClientProtocolException, IOException {
        HttpPost post = new HttpPost( url.toURI() );
        
        setHeaders( post, headers );
        
        if( in != null ) {
            post.setEntity( new InputStreamEntity( in, contentLength ) );
        } else {
            post.setEntity( new ByteArrayEntity( new byte[0] ) );
        }
        
        return httpClient.execute( post );
    }
    
    private HttpResponse restPost( URL url, Map<String,String> headers, BufferSegment data ) throws URISyntaxException, ClientProtocolException, IOException {
        HttpPost post = new HttpPost( url.toURI() );
        
        setHeaders( post, headers );
        
        if( data != null ) {
            if( data.getOffset() == 0 && (data.getSize() == data.getBuffer().length ) ) {
                // use the native byte array
                post.setEntity( new ByteArrayEntity( data.getBuffer() ) );
            } else {
                post.setEntity( new InputStreamEntity( 
                        new ByteArrayInputStream(data.getBuffer(), data.getOffset(), data.getSize()),
                        data.getSize() ) );
            }
        } else {
            post.setEntity( new ByteArrayEntity( new byte[0] ) );
        }
        
        return httpClient.execute( post );
    }

    private HttpResponse restDelete(URL url, Map<String, String> headers) throws URISyntaxException, ClientProtocolException, IOException {
        HttpDelete delete = new HttpDelete( url.toURI() );
        
        setHeaders(delete, headers);
        
        return httpClient.execute( delete );
    }

    private HttpResponse restGet(URL url, Map<String, String> headers) throws URISyntaxException, ClientProtocolException, IOException {
        HttpGet get = new HttpGet( url.toURI() );
        
        setHeaders(get, headers);
        
        return httpClient.execute( get );
    }

    private HttpResponse restPut(URL url, Map<String, String> headers,
            BufferSegment data) throws ClientProtocolException, IOException, URISyntaxException {
        
        HttpPut put = new HttpPut( url.toURI() );
        
        setHeaders( put, headers );
        
        if( data != null ) {
            if( data.getOffset() == 0 && (data.getSize() == data.getBuffer().length ) ) {
                // use the native byte array
                put.setEntity( new ByteArrayEntity( data.getBuffer() ) );
            } else {
                put.setEntity( new InputStreamEntity( 
                        new ByteArrayInputStream(data.getBuffer(), data.getOffset(), data.getSize()),
                        data.getSize() ) );
            }
        } else {
            put.setEntity( new ByteArrayEntity( new byte[0] ) );
        }
        
        return httpClient.execute( put );

    }

    private HttpResponse restPut(URL url, Map<String, String> headers,
            InputStream in, long contentLength) throws URISyntaxException, ClientProtocolException, IOException {
        HttpPut put = new HttpPut( url.toURI() );
        
        setHeaders( put, headers );
        
        if( in != null ) {
            put.setEntity( new InputStreamEntity( in, contentLength ) );
        } else {
            put.setEntity( new ByteArrayEntity( new byte[0] ) );
        }
        
        return httpClient.execute( put );
    }

    private HttpResponse restHead(URL url, Map<String, String> headers) throws URISyntaxException, ClientProtocolException, IOException {
        HttpHead head = new HttpHead( url.toURI() );
        
        setHeaders( head, headers );
        
        return httpClient.execute( head );
    }

    private void setHeaders( AbstractHttpMessage request, Map<String, String> headers ) {
        for( String headerName : headers.keySet() ) {
            request.addHeader( headerName, headers.get( headerName ) );
        }
    }
    
    private void finishRequest(HttpResponse response) throws IOException {
        if( response.getEntity() != null ) {
            cleanup( response );
        }
    }

    private void readMetadata(MetadataList meta, Header firstHeader,
            boolean listable) {
        if( firstHeader != null ) {
            super.readMetadata(meta, firstHeader.getValue(), listable );
        }
    }

    private void cleanup( HttpResponse response ) throws IOException {
    	if( response.getEntity() != null ) {
    		response.getEntity().consumeContent();
    	}
    }


}
