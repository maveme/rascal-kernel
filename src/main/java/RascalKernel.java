/** 
 * Copyright (c) 2017, Mauricio Verano Merino, Centrum Wiskunde & Informatica (CWI) 
 * All rights reserved. 
 *  
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met: 
 *  
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer. 
 *  
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution. 
 *  
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. 
 */ 

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.repl.CompletionResult;
import org.rascalmpl.repl.ILanguageProtocol;
import org.rascalmpl.repl.RascalInterpreterREPL;
import org.rascalmpl.shell.ShellEvaluatorFactory;
import org.zeromq.ZMQ.Socket;
import com.google.gson.JsonObject;
import communication.Header;
import entities.ContentExecuteInput;
import entities.ContentStream;
import entities.reply.ContentCompleteReply;
import entities.reply.ContentExecuteReplyOk;
import entities.reply.ContentExecuteResult;
import entities.reply.ContentIsCompleteReply;
import entities.reply.ContentKernelInfoReply;
import entities.reply.ContentShutdownReply;
import entities.request.ContentCompleteRequest;
import entities.request.ContentExecuteRequest;
import entities.request.ContentIsCompleteRequest;
import entities.request.ContentShutdownRequest;
import entities.util.MessageType;
import entities.util.Status;
import server.JupyterServer;

public class RascalKernel extends JupyterServer{

	// -----------------------------------------------------------------
	// Fields
	// -----------------------------------------------------------------

	private int executionNumber;

	private ILanguageProtocol language;

	private StringWriter stdout;

	private StringWriter stderr;

	// -----------------------------------------------------------------
	// Constructor
	// -----------------------------------------------------------------

	public RascalKernel(String connectionFilePath) throws Exception {
		super(connectionFilePath);
		executionNumber = 1;
		stdout = new StringWriter();
		stderr = new StringWriter();
		this.language = makeInterpreter(null, null);
		this.language.initialize(stdout, stderr);
		startServer();
	}

	// -----------------------------------------------------------------
	// Methods
	// -----------------------------------------------------------------

	@Override
	public void processExecuteRequest(Header parentHeader, ContentExecuteRequest contentExecuteRequest) {
		if(!contentExecuteRequest.isSilent())
		{
			if(contentExecuteRequest.isStoreHistory())
			{
				sendMessage(getCommunication().getPublish(),createHeader(parentHeader.getSession(), MessageType.EXECUTE_INPUT), parentHeader, new JsonObject(), new ContentExecuteInput(contentExecuteRequest.getCode(), executionNumber));

				try {
					Map<String, String> data = new HashMap<>();
					Map<String, String> metadata = new HashMap<>();

					this.language.handleInput(contentExecuteRequest.getCode(), data, metadata);
					sendMessage(getCommunication().getRequests(), createHeader(parentHeader.getSession(), MessageType.EXECUTE_REPLY), parentHeader, new JsonObject(), new ContentExecuteReplyOk(executionNumber));

					if(!stdout.toString().trim().equals("")){
						sendMessage(getCommunication().getPublish(), createHeader(parentHeader.getSession(), MessageType.STREAM), parentHeader, new JsonObject(), new ContentStream("stdout", stdout.toString()));
						stdout.getBuffer().setLength(0);
						stdout.flush();
					}

					if(!stderr.toString().trim().equals("")){
						sendMessage(getCommunication().getPublish(), createHeader(parentHeader.getSession(), MessageType.STREAM), parentHeader, new JsonObject(), new ContentStream("stderr", stderr.toString()));
						stderr.getBuffer().setLength(0);
						stderr.flush();
					}

					// sends the result
					if(!data.isEmpty())
					{
						ContentExecuteResult content = new ContentExecuteResult(executionNumber, data, metadata);
						sendMessage(getCommunication().getPublish(), createHeader(parentHeader.getSession(), MessageType.EXECUTE_RESULT), parentHeader, new JsonObject(), content);
					}

				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			else{
				// TODO evaluate user code 
			}
			executionNumber ++;
		}
		else{
			// No broadcast output on the IOPUB channel.
			// Don't have an execute_result.
			sendMessage(getCommunication().getRequests(), createHeader(parentHeader.getSession(), MessageType.EXECUTE_REPLY), parentHeader, new JsonObject(), new ContentExecuteReplyOk(executionNumber));
		}
	}

	@Override
	public void processHistoryRequest(Header parentHeader) {
		// TODO This is only for clients to explicitly request history from a kernel
	}
	@Override
	public void processKernelInfoRequest(Header parentHeader){
		sendMessage(getCommunication().getRequests(), createHeader(parentHeader.getSession(), MessageType.KERNEL_INFO_REPLY), parentHeader, new JsonObject(), new ContentKernelInfoReply());
	}

	@Override
	public void processShutdownRequest(Socket socket, Header parentHeader, ContentShutdownRequest contentShutdown) {
		boolean restart = false;
		if(contentShutdown.getRestart())
		{
			restart = true;
			// TODO: how should I restart rascal?
		}
		else{
			this.language.stop();
			getCommunication().getRequests().close();
			getCommunication().getPublish().close();
			getCommunication().getControl().close();
			getCommunication().getContext().close();
			getCommunication().getContext().term();
			System.exit(-1);
		}
		sendMessage(socket, createHeader(parentHeader.getSession(), MessageType.SHUTDOWN_REPLY), parentHeader, new JsonObject(), new ContentShutdownReply(restart));
	}

	/**
	 * This method is executed when the kernel receives a is_complete_request message.
	 */
	@Override
	public void processIsCompleteRequest(Header header, ContentIsCompleteRequest request) {
		//TODO: Rascal supports different statuses? (e.g. complete, incomplete, invalid or unknown?
		String status, indent="";
		if(this.language.isStatementComplete(request.getCode())){
			System.out.println("COMPLETO");
			status = Status.COMPLETE;
		}
		else{
			status = Status.INCOMPLETE;
			indent = "??????";
		}
		sendMessage(getCommunication().getRequests(), createHeader(header.getSession(), MessageType.IS_COMPLETE_REPLY), header, new JsonObject(), new ContentIsCompleteReply(status, indent));
	}

	@Override
	public void processCompleteRequest(Header parentHeader, ContentCompleteRequest request) {
		int cursorStart =0;
		ArrayList<String> sugestions;
		if(request.getCode().startsWith("import ")){
			cursorStart=7;
		}
		CompletionResult result =this.language.completeFragment(request.getCode(), request.getCursorPosition());
		if(result != null)
			sugestions = (ArrayList<String>)result.getSuggestions();
		else 
			sugestions = null;
		ContentCompleteReply content = new ContentCompleteReply(sugestions, cursorStart, request.getCode().length(), new HashMap<String, String>(), Status.OK);
		sendMessage(getCommunication().getRequests(), createHeader(parentHeader.getSession(), MessageType.COMPLETE_REPLY), parentHeader, new JsonObject(), content);
	}

	@Override
	public ILanguageProtocol makeInterpreter(String moduleName, String variableName) throws IOException, URISyntaxException {
		return new RascalInterpreterREPL() {
			@Override
			protected Evaluator constructEvaluator(Writer stdout, Writer stderr) {
				return ShellEvaluatorFactory.getDefaultEvaluator(new PrintWriter(stdout), new PrintWriter(stderr));
			}
		};
	}

	// -----------------------------------------------------------------
	// Execution
	// -----------------------------------------------------------------

	public static void main(String[] args) {
		try {
			RascalKernel mv =  new RascalKernel(args[0]);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
