package com.emc.atmos.sync;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.log4j.Logger;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;

public abstract class TaskNode implements Callable<TaskResult> {
	private static final Logger l4j = Logger.getLogger(TaskNode.class);
	
	protected Set<TaskNode> parentTasks;
	protected SimpleDirectedGraph<TaskNode, DefaultEdge> graph;
	private boolean queued;
	
	public TaskNode( Set<TaskNode> parentTasks ) {
		this.parentTasks = parentTasks;
		if( parentTasks == null ) {
			this.parentTasks = new HashSet<TaskNode>();
		}
	}
	
	public TaskNode() {
		this.parentTasks = new HashSet<TaskNode>();
	}
	
	public void addParent( TaskNode parent ) {
		parentTasks.add( parent );
		if( graph != null ) {
			synchronized (graph) {
				graph.addEdge(parent, this);
			}
		}
	}
	
	public void addToGraph( SimpleDirectedGraph<TaskNode, DefaultEdge> graph) {
		this.graph = graph;
		
		synchronized( graph ) {
			graph.addVertex( this );
			for( TaskNode parent : parentTasks ) {
				try {
					graph.addEdge(parent, this);
				} catch( IllegalArgumentException e ) {
					// The parent task probably already completed.
					l4j.debug( "Failed to add edge, parent probably already completed: " + e );
				}
			}
		}
	}

	@Override
	public TaskResult call() throws Exception {
		if( graph == null ) {
			throw new IllegalStateException( "Task not in graph?" );
		}

		TaskResult result = null;
		try {
			result = execute();
		} catch( Exception e ) {
			result = new TaskResult( false );
		}
		
		// Completed.  Remove from graph.
		removeFromGraph();
		
		return result;
	}
	
	private void removeFromGraph() {
		if( graph == null ) {
			throw new IllegalStateException( "Task not in graph?" );
		}
		synchronized (graph) {
			graph.removeVertex(this);
		}
	}

	protected abstract TaskResult execute() throws Exception;

	public void setQueued(boolean queued) {
		this.queued = queued;
	}

	public boolean isQueued() {
		return queued;
	}

}
