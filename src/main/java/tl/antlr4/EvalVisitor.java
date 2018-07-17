package tl.antlr4;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;

import org.apache.commons.math3.exception.DimensionMismatchException;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import tl.antlr4.TLParser.*;

public class EvalVisitor extends TLBaseVisitor<TLValue> {

    private static final String DIMENSION_MISMATCH = "Dimension mismatch! ";
    private static final String TRANSPOSE_ARGUMENT_MISMATCH = "Only vectors and matrices can be transposed!";
    private static final String DIMENSIONS_ARGUMENTS_MISMATCH = "rows() and columns() is working only with vectors and matrices";
    private static final String NOT_SQUARED_MATRIX = "Determinant can be calculated only of squared matrix";
    private static final String ELEMENTS_SUM_NOT_MATRIX = "matrixSum() works only with vector and matrix";

	private static ReturnValue returnValue = new ReturnValue();
    private Scope scope;
    private Map<String, Function> functions;
    
    EvalVisitor(Scope scope, Map<String, Function> functions) {
        this.scope = scope;
        this.functions = functions;
    }

    // functionDecl
    @Override
    public TLValue visitFunctionDecl(FunctionDeclContext ctx) {
        return TLValue.VOID;
    }
    
    // list: '[' exprList? ']'
    @Override
    public TLValue visitList(ListContext ctx) {
        List<TLValue> list = new ArrayList<>();
        if (ctx.exprList() != null) {
	        for(ExpressionContext ex: ctx.exprList().expression()) {
	            list.add(this.visit(ex));
	        }
        }
        return new TLValue(list);
    }
    
    
    // '-' expression                           #unaryMinusExpression
    @Override
    public TLValue visitUnaryMinusExpression(UnaryMinusExpressionContext ctx) {
    	TLValue v = this.visit(ctx.expression());
    	if (!v.isNumber()) {
    	    throw new EvalException(ctx);
        }
    	return new TLValue(-1 * v.asDouble());
    }

    // '!' expression                           #notExpression
    @Override
    public TLValue visitNotExpression(NotExpressionContext ctx) {
    	TLValue v = this.visit(ctx.expression());
    	if(!v.isBoolean()) {
    	    throw new EvalException(ctx);
        }
    	return new TLValue(!v.asBoolean());
    }

    // expression '^' expression                #powerExpression
    @Override
    public TLValue visitPowerExpression(PowerExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	if (lhs.isNumber() && rhs.isNumber()) {
    		return new TLValue(Math.pow(lhs.asDouble(), rhs.asDouble()));
    	}

    	if (lhs.isList() && rhs.isNumber()){
    	    if (lhs.isMatrix()){
                double[][] lhsMatrix = TLValue.toMatrix(lhs);
                for (int i = 0; i < lhsMatrix.length; i++){
                    for (int j = 0; j < lhsMatrix[i].length; j++){
                        lhsMatrix[i][j] = Math.pow(lhsMatrix[i][j], rhs.asDouble());
                    }
                }
                RealMatrix result = MatrixUtils.createRealMatrix(lhsMatrix);
                return TLValue.fromMatrix(result);
            }
            else if (lhs.isVector()){
                double[] lhsMatrix = TLValue.toVector(lhs);

                for (int i = 0; i < lhsMatrix.length; i++) {
                    lhsMatrix[i] = Math.pow(lhsMatrix[i], rhs.asDouble());
                }
                RealMatrix result = MatrixUtils.createColumnRealMatrix(lhsMatrix);
                return TLValue.fromMatrix(result);
            }
        }

    	throw new EvalException(ctx);
    }

    // expression op=( '*' | '/' | '%' ) expression         #multExpression
    @Override
    public TLValue visitMultExpression(MultExpressionContext ctx) {
        switch (ctx.op.getType()) {
            case TLLexer.Multiply:
                return multiply(ctx);
            case TLLexer.Divide:
                return divide(ctx);
            case TLLexer.Modulus:
                return modulus(ctx);
            default:
                throw new RuntimeException("unknown operator type: " + ctx.op.getType());
        }
    }

    // expression op=( '+' | '-' ) expression               #addExpression
    @Override
    public TLValue visitAddExpression(AddExpressionContext ctx) {
        switch (ctx.op.getType()) {
            case TLLexer.Add:
                return add(ctx);
            case TLLexer.Subtract:
                return subtract(ctx);
            default:
                throw new RuntimeException("unknown operator type: " + ctx.op.getType());
        }
    }

    // expression op=( '>=' | '<=' | '>' | '<' ) expression #compExpression
    @Override
    public TLValue visitCompExpression(CompExpressionContext ctx) {
        switch (ctx.op.getType()) {
            case TLLexer.LT:
                return lt(ctx);
            case TLLexer.LTEquals:
                return ltEq(ctx);
            case TLLexer.GT:
                return gt(ctx);
            case TLLexer.GTEquals:
                return gtEq(ctx);
            default:
                throw new RuntimeException("unknown operator type: " + ctx.op.getType());
        }
    }

    // expression op=( '==' | '!=' ) expression             #eqExpression
    @Override
    public TLValue visitEqExpression(EqExpressionContext ctx) {
        switch (ctx.op.getType()) {
            case TLLexer.Equals:
                return eq(ctx);
            case TLLexer.NEquals:
                return nEq(ctx);
            default:
                throw new RuntimeException("unknown operator type: " + ctx.op.getType());
        }
    }
    
    public TLValue multiply(MultExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	if(lhs == null || rhs == null) {
    		System.err.println("lhs "+ lhs+ " rhs "+rhs);
    	    throw new EvalException(ctx);
    	}
    	
    	// number * number
        if(lhs.isNumber() && rhs.isNumber()) {
            return new TLValue(lhs.asDouble() * rhs.asDouble());
        }

        // string * number
        if(lhs.isString() && rhs.isNumber()) {
            StringBuilder str = new StringBuilder();
            int stop = rhs.asDouble().intValue();
            for(int i = 0; i < stop; i++) {
                str.append(lhs.asString());
            }
            return new TLValue(str.toString());
        }

        // list * number
        if(lhs.isList() && rhs.isNumber()) {
            List<TLValue> total = new ArrayList<>();
            int stop = rhs.asDouble().intValue();
            for(int i = 0; i < stop; i++) {
                total.addAll(lhs.asList());
            }
            return new TLValue(total);
        }

        if (lhs.isList() && rhs.isList()) {
    	    if (lhs.isVector() && rhs.isVector()) {
                return multiplyVectors(ctx, lhs, rhs);
            }
            boolean leftIsVector = (lhs.isVector() && rhs.isMatrix());
            if (leftIsVector || (lhs.isMatrix() && rhs.isVector())) {
                return multiplyVectorByMatrix(ctx, lhs, rhs, leftIsVector);
            }
            if (lhs.isMatrix() && rhs.isMatrix()) {
                return multiplyMatrixByMatrix(ctx, lhs, rhs);
            }
        }
         	
    	throw new EvalException(ctx);
    }

    private TLValue multiplyMatrixByMatrix(MultExpressionContext ctx, TLValue lhs, TLValue rhs) {
        double[][] lhsMatrix = TLValue.toMatrix(lhs);
        double[][] rhsMatrox = TLValue.toMatrix(rhs);
        RealMatrix lhsRealMatrix = MatrixUtils.createRealMatrix(lhsMatrix);
        RealMatrix rhsRealMatrix = MatrixUtils.createRealMatrix(rhsMatrox);
        try {
            RealMatrix result = lhsRealMatrix.multiply(rhsRealMatrix);
            return TLValue.fromMatrix(result);
        } catch (DimensionMismatchException e) {
            throw new EvalException(DIMENSION_MISMATCH + e.getMessage(), ctx);
        }
    }

    private TLValue multiplyVectorByMatrix(MultExpressionContext ctx, TLValue lhs, TLValue rhs, boolean leftIsVector) {
        if (leftIsVector) {
            double[] lfsVector = TLValue.toVector(lhs);
            double[][] rhsMatrix = TLValue.toMatrix(rhs);
            RealMatrix lfsRealVector = MatrixUtils.createColumnRealMatrix(lfsVector).transpose();
            RealMatrix rhsRealMatrix = MatrixUtils.createRealMatrix(rhsMatrix);
            try {
                RealMatrix result = lfsRealVector.multiply(rhsRealMatrix);
                return TLValue.fromMatrix(result);
            } catch (DimensionMismatchException e) {
                throw new EvalException(DIMENSION_MISMATCH + e.getMessage(), ctx);
            }
        }
        double[][] lfsMatrix = TLValue.toMatrix(lhs);
        double[] rhsVector = TLValue.toVector(rhs);
        RealMatrix lhsRealMatrix = MatrixUtils.createRealMatrix(lfsMatrix);
        RealMatrix rhsRealVector = MatrixUtils.createColumnRealMatrix(rhsVector);
        try {
            RealMatrix result = lhsRealMatrix.multiply(rhsRealVector);
            return TLValue.fromMatrix(result);
        } catch (DimensionMismatchException e) {
            throw new EvalException(DIMENSION_MISMATCH + e.getMessage(), ctx);
        }
    }

    private TLValue multiplyVectors(MultExpressionContext ctx, TLValue lhs, TLValue rhs) {
        double[] lhsAsDoubleArray = TLValue.toVector(lhs);
        double[] rhsAsDoubleArray = TLValue.toVector(rhs);
        RealVector leftVector = MatrixUtils.createRealVector(lhsAsDoubleArray);
        RealVector rightVector = MatrixUtils.createRealVector(rhsAsDoubleArray);
        try {
            RealVector result = leftVector.ebeMultiply(rightVector);
            List<TLValue> resultAsTemplate = Arrays.stream(result.toArray()).mapToObj(x -> new TLValue(x))
                    .collect(Collectors.toList());
            return new TLValue(resultAsTemplate);
        } catch (DimensionMismatchException e) {
            throw new EvalException(DIMENSION_MISMATCH + e.getMessage(), ctx);
        }
    }
    
    private TLValue divide(MultExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	if (lhs.isNumber() && rhs.isNumber()) {
    		return new TLValue(lhs.asDouble() / rhs.asDouble());
    	}
    	throw new EvalException(ctx);
    }

	private TLValue modulus(MultExpressionContext ctx) {
		TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	if (lhs.isNumber() && rhs.isNumber()) {
    		return new TLValue(lhs.asDouble() % rhs.asDouble());
    	}
    	throw new EvalException(ctx);
	}

    private TLValue add(AddExpressionContext ctx) {
        TLValue lhs = this.visit(ctx.expression(0));
        TLValue rhs = this.visit(ctx.expression(1));

        if(lhs == null || rhs == null) {
            throw new EvalException(ctx);
        }

        // number + number
        if(lhs.isNumber() && rhs.isNumber()) {
            return new TLValue(lhs.asDouble() + rhs.asDouble());
        }


        // vector + vector | matrix + matrix
        if (lhs.isList() && rhs.isList()) {
            RealMatrix leftMatrix;
            RealMatrix rightMatrix;
            if (lhs.isMatrix() && rhs.isMatrix()) {
                double[][] lhsMatrix = TLValue.toMatrix(lhs);
                leftMatrix = MatrixUtils.createRealMatrix(lhsMatrix);
                double[][] rhsMatrix = TLValue.toMatrix(rhs);
                rightMatrix = MatrixUtils.createRealMatrix(rhsMatrix);
                return addMatrices(ctx, leftMatrix, rightMatrix, false);
            }
            if (lhs.isVector() && rhs.isVector()) {
                double[] lhsMatrix = TLValue.toVector(lhs);
                leftMatrix = MatrixUtils.createColumnRealMatrix(lhsMatrix);
                double[] rhsMatrix = TLValue.toVector(rhs);
                rightMatrix = MatrixUtils.createColumnRealMatrix(rhsMatrix);
                return addMatrices(ctx, leftMatrix, rightMatrix, false);
            }
        }


        // list + any
        if(lhs.isList()) {
            List<TLValue> list = lhs.asList();
            list.add(rhs);
            return new TLValue(list);
        }

        // string + any
        if(lhs.isString()) {
            return new TLValue(lhs.asString() + "" + rhs.toString());
        }

        // any + string
        if(rhs.isString()) {
            return new TLValue(lhs.toString() + "" + rhs.asString());
        }

        return new TLValue(lhs.toString() + rhs.toString());
    }



    private TLValue addMatrices(AddExpressionContext ctx, RealMatrix leftMatrix, RealMatrix rightMatrix, boolean substract) {
        try {
            RealMatrix result;
            if (substract){
                result = leftMatrix.subtract(rightMatrix);
            } else {
                result = leftMatrix.add(rightMatrix);
            }
            return TLValue.fromMatrix(result);
        } catch (DimensionMismatchException e) {
            throw new EvalException(DIMENSION_MISMATCH + e.getMessage(), ctx);
        }
    }


    private TLValue subtract(AddExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	if (lhs.isNumber() && rhs.isNumber()) {
    		return new TLValue(lhs.asDouble() - rhs.asDouble());
    	}


        // vector + vector | matrix + matrix
        if (lhs.isList() && rhs.isList()) {
            RealMatrix leftMatrix;
            RealMatrix rightMatrix;
            if (lhs.isMatrix() && rhs.isMatrix()) {
                double[][] lhsMatrix = TLValue.toMatrix(lhs);
                leftMatrix = MatrixUtils.createRealMatrix(lhsMatrix);
                double[][] rhsMatrix = TLValue.toMatrix(rhs);
                rightMatrix = MatrixUtils.createRealMatrix(rhsMatrix);
                return addMatrices(ctx, leftMatrix, rightMatrix, true);
            }
            if (lhs.isVector() && rhs.isVector()) {
                double[] lhsMatrix = TLValue.toVector(lhs);
                leftMatrix = MatrixUtils.createColumnRealMatrix(lhsMatrix);
                double[] rhsMatrix = TLValue.toVector(rhs);
                rightMatrix = MatrixUtils.createColumnRealMatrix(rhsMatrix);
                return addMatrices(ctx, leftMatrix, rightMatrix, true);
            }
        }



        if (lhs.isList()) {
            List<TLValue> list = lhs.asList();
            list.remove(rhs);
            return new TLValue(list);
        }
    	throw new EvalException(ctx);
    }

    private TLValue gtEq(CompExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	if (lhs.isNumber() && rhs.isNumber()) {
    		return new TLValue(lhs.asDouble() >= rhs.asDouble());
    	}
    	if(lhs.isString() && rhs.isString()) {
            return new TLValue(lhs.asString().compareTo(rhs.asString()) >= 0);
        }
    	throw new EvalException(ctx);
    }

    private TLValue ltEq(CompExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	if (lhs.isNumber() && rhs.isNumber()) {
    		return new TLValue(lhs.asDouble() <= rhs.asDouble());
    	}
    	if(lhs.isString() && rhs.isString()) {
            return new TLValue(lhs.asString().compareTo(rhs.asString()) <= 0);
        }
    	throw new EvalException(ctx);
    }

    private TLValue gt(CompExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	if (lhs.isNumber() && rhs.isNumber()) {
    		return new TLValue(lhs.asDouble() > rhs.asDouble());
    	}
    	if(lhs.isString() && rhs.isString()) {
            return new TLValue(lhs.asString().compareTo(rhs.asString()) > 0);
        }
    	throw new EvalException(ctx);
    }

    private TLValue lt(CompExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	if (lhs.isNumber() && rhs.isNumber()) {
    		return new TLValue(lhs.asDouble() < rhs.asDouble());
    	}
    	if(lhs.isString() && rhs.isString()) {
            return new TLValue(lhs.asString().compareTo(rhs.asString()) < 0);
        }
    	throw new EvalException(ctx);
    }

    private TLValue eq(EqExpressionContext ctx) {
        TLValue lhs = this.visit(ctx.expression(0));
        TLValue rhs = this.visit(ctx.expression(1));
        if (lhs == null) {
        	throw new EvalException(ctx);
        }
        return new TLValue(lhs.equals(rhs));
    }

    private TLValue nEq(EqExpressionContext ctx) {
        TLValue lhs = this.visit(ctx.expression(0));
        TLValue rhs = this.visit(ctx.expression(1));
        return new TLValue(!lhs.equals(rhs));
    }

    // expression '&&' expression               #andExpression
    @Override
    public TLValue visitAndExpression(AndExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	
    	if(!lhs.isBoolean() || !rhs.isBoolean()) {
    	    throw new EvalException(ctx);
        }
		return new TLValue(lhs.asBoolean() && rhs.asBoolean());
    }

    // expression '||' expression               #orExpression
    @Override
    public TLValue visitOrExpression(OrExpressionContext ctx) {
    	TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	
    	if(!lhs.isBoolean() || !rhs.isBoolean()) {
    	    throw new EvalException(ctx);
        }
		return new TLValue(lhs.asBoolean() || rhs.asBoolean());
    }

    // expression '?' expression ':' expression #ternaryExpression
    @Override
    public TLValue visitTernaryExpression(TernaryExpressionContext ctx) {
    	TLValue condition = this.visit(ctx.expression(0));
    	if (condition.asBoolean()) {
    		return new TLValue(this.visit(ctx.expression(1)));
    	} else {
    		return new TLValue(this.visit(ctx.expression(2)));
    	}
    }

    // expression In expression                 #inExpression
	@Override
	public TLValue visitInExpression(InExpressionContext ctx) {
		TLValue lhs = this.visit(ctx.expression(0));
    	TLValue rhs = this.visit(ctx.expression(1));
    	
    	if (rhs.isList()) {
    		for(TLValue val: rhs.asList()) {
    			if (val.equals(lhs)) {
    				return new TLValue(true);
    			}
    		}
    		return new TLValue(false);
    	}
    	throw new EvalException(ctx);
	}
	
    // Number                                   #numberExpression
    @Override
    public TLValue visitNumberExpression(NumberExpressionContext ctx) {
        return new TLValue(Double.valueOf(ctx.getText()));
    }

    // Bool                                     #boolExpression
    @Override
    public TLValue visitBoolExpression(BoolExpressionContext ctx) {
        return new TLValue(Boolean.valueOf(ctx.getText()));
    }

    // Null                                     #nullExpression
    @Override
    public TLValue visitNullExpression(NullExpressionContext ctx) {
        return TLValue.NULL;
    }

    private TLValue resolveIndexes(TLValue val, List<ExpressionContext> indexes) {
    	for (ExpressionContext ec: indexes) {
    		TLValue idx = this.visit(ec);
    		if (!idx.isNumber() || (!val.isList() && !val.isString()) ) {
        		throw new EvalException("Problem resolving indexes on "+val+" at "+idx, ec);
    		}
    		int i = idx.asDouble().intValue();
    		if (val.isString()) {
    			val = new TLValue(val.asString().substring(i, i+1));
    		} else {
    			val = val.asList().get(i);
    		}
    	}
    	return val;
    }
    
    private void setAtIndex(ParserRuleContext ctx, List<ExpressionContext> indexes, TLValue val, TLValue newVal) {
    	if (!val.isList()) {
    		throw new EvalException(ctx);
    	}
    	for (int i = 0; i < indexes.size() - 1; i++) {
    		TLValue idx = this.visit(indexes.get(i));
    		if (!idx.isNumber()) {
        		throw new EvalException(ctx);
    		}
    		val = val.asList().get(idx.asDouble().intValue());
    	}
    	TLValue idx = this.visit(indexes.get(indexes.size() - 1));
		if (!idx.isNumber()) {
    		throw new EvalException(ctx);
		}
    	val.asList().set(idx.asDouble().intValue(), newVal);
    }
    
    // functionCall indexes?                    #functionCallExpression
    @Override
    public TLValue visitFunctionCallExpression(FunctionCallExpressionContext ctx) {
    	TLValue val = this.visit(ctx.functionCall());
    	if (ctx.indexes() != null) {
        	List<ExpressionContext> exps = ctx.indexes().expression();
        	val = resolveIndexes(val, exps);
        }
    	return val;
    }

    // list indexes?                            #listExpression
    @Override
    public TLValue visitListExpression(ListExpressionContext ctx) {
    	TLValue val = this.visit(ctx.list());
    	if (ctx.indexes() != null) {
        	List<ExpressionContext> exps = ctx.indexes().expression();
        	val = resolveIndexes(val, exps);
        }
    	return val;
    }

    // Identifier indexes?                      #identifierExpression
    @Override
    public TLValue visitIdentifierExpression(IdentifierExpressionContext ctx) {
        String id = ctx.Identifier().getText();
        TLValue val = scope.resolve(id);
        
        if (ctx.indexes() != null) {
        	List<ExpressionContext> exps = ctx.indexes().expression();
        	val = resolveIndexes(val, exps);
        }
        return val;
    }

    // String indexes?                          #stringExpression
    @Override
    public TLValue visitStringExpression(StringExpressionContext ctx) {
        String text = ctx.getText();
        text = text.substring(1, text.length() - 1).replaceAll("\\\\(.)", "$1");
        TLValue val = new TLValue(text);
        if (ctx.indexes() != null) {
        	List<ExpressionContext> exps = ctx.indexes().expression();
        	val = resolveIndexes(val, exps);
        }
        return val;
    }

    // '(' expression ')' indexes?              #expressionExpression
    @Override
    public TLValue visitExpressionExpression(ExpressionExpressionContext ctx) {
        TLValue val = this.visit(ctx.expression());
        if (ctx.indexes() != null) {
        	List<ExpressionContext> exps = ctx.indexes().expression();
        	val = resolveIndexes(val, exps);
        }
        return val;
    }

    // Input '(' String? ')'                    #inputExpression
    @Override
    public TLValue visitInputExpression(InputExpressionContext ctx) {
    	TerminalNode inputString = ctx.String();
		try {
			if (inputString != null) {
				String text = inputString.getText();
		        text = text.substring(1, text.length() - 1).replaceAll("\\\\(.)", "$1");
				return new TLValue(new String(Files.readAllBytes(Paths.get(text))));
			} else {
				BufferedReader buffer = new BufferedReader(new InputStreamReader(System.in));
				return new TLValue(buffer.readLine());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
    }

    
    // assignment
    // : Identifier indexes? '=' expression
    // ;
    @Override
    public TLValue visitAssignment(AssignmentContext ctx) {
        TLValue newVal = this.visit(ctx.expression());
        if (ctx.indexes() != null) {
        	TLValue val = scope.resolve(ctx.Identifier().getText());
        	List<ExpressionContext> exps = ctx.indexes().expression();
        	setAtIndex(ctx, exps, val, newVal);
        } else {
        	String id = ctx.Identifier().getText();        	
        	scope.assign(id, newVal);
        }
        return TLValue.VOID;
    }

    // Identifier '(' exprList? ')' #identifierFunctionCall
    @Override
    public TLValue visitIdentifierFunctionCall(IdentifierFunctionCallContext ctx) {
        List<ExpressionContext> params = ctx.exprList() != null ? ctx.exprList().expression() : new ArrayList<ExpressionContext>();
        String id = ctx.Identifier().getText() + params.size();
        Function function;      
        if ((function = functions.get(id)) != null) {
            return function.invoke(params, functions, scope);
        }
        throw new EvalException(ctx);
    }

    // Println '(' expression? ')'  #printlnFunctionCall
    @Override
    public TLValue visitPrintlnFunctionCall(PrintlnFunctionCallContext ctx) {
        System.out.println(this.visit(ctx.expression()));
        return TLValue.VOID;
    }

    // Print '(' expression ')'     #printFunctionCall
    @Override
    public TLValue visitPrintFunctionCall(PrintFunctionCallContext ctx) {
        System.out.print(this.visit(ctx.expression()));
        return TLValue.VOID;
    }

    // Assert '(' expression ')'    #assertFunctionCall
    @Override
    public TLValue visitAssertFunctionCall(AssertFunctionCallContext ctx) {
    	TLValue value = this.visit(ctx.expression());

        if(!value.isBoolean()) {
            throw new EvalException(ctx);
        }

        if(!value.asBoolean()) {
            throw new AssertionError("Failed Assertion "+ctx.expression().getText()+" line:"+ctx.start.getLine());
        }

        return TLValue.VOID;
    }

    // Size '(' expression ')'      #sizeFunctionCall
    @Override
    public TLValue visitSizeFunctionCall(SizeFunctionCallContext ctx) {
    	TLValue value = this.visit(ctx.expression());

        if(value.isString()) {
            return new TLValue(value.asString().length());
        }

        if(value.isList()) {
            return new TLValue(value.asList().size());
        }

        throw new EvalException(ctx);
    }

    // ifStatement
    //  : ifStat elseIfStat* elseStat? End
    //  ;
    //
    // ifStat
    //  : If expression Do block
    //  ;
    //
    // elseIfStat
    //  : Else If expression Do block
    //  ;
    //
    // elseStat
    //  : Else Do block
    //  ;
    @Override
    public TLValue visitIfStatement(IfStatementContext ctx) {

        // if ...
        if(this.visit(ctx.ifStat().expression()).asBoolean()) {
            return this.visit(ctx.ifStat().block());
        }

        // else if ...
        for(int i = 0; i < ctx.elseIfStat().size(); i++) {
            if(this.visit(ctx.elseIfStat(i).expression()).asBoolean()) {
                return this.visit(ctx.elseIfStat(i).block());
            }
        }

        // else ...
        if(ctx.elseStat() != null) {
            return this.visit(ctx.elseStat().block());
        }

        return TLValue.VOID;
    }
    
    // block
    // : (statement | functionDecl)* (Return expression)?
    // ;
    @Override
    public TLValue visitBlock(BlockContext ctx) {
    		
    	scope = new Scope(scope); // create new local scope
        for (StatementContext sx: ctx.statement()) {
            this.visit(sx);
        }
        ExpressionContext ex;
        if ((ex = ctx.expression()) != null) {
        	returnValue.value = this.visit(ex);
        	scope = scope.parent();
        	throw returnValue;
        }
        scope = scope.parent();
        return TLValue.VOID;
    }
    
    // forStatement
    // : For Identifier '=' expression To expression OBrace block CBrace
    // ;
    @Override
    public TLValue visitForStatement(ForStatementContext ctx) {
        int start = this.visit(ctx.expression(0)).asDouble().intValue();
        int stop = this.visit(ctx.expression(1)).asDouble().intValue();
        for(int i = start; i <= stop; i++) {
            scope.assign(ctx.Identifier().getText(), new TLValue(i));
            TLValue returnValue = this.visit(ctx.block());
            if(returnValue != TLValue.VOID) {
                return returnValue;
            }
        }
        return TLValue.VOID;
    }
    
    // whileStatement
    // : While expression OBrace block CBrace
    // ;
    @Override
    public TLValue visitWhileStatement(WhileStatementContext ctx) {
        while( this.visit(ctx.expression()).asBoolean() ) {
            TLValue returnValue = this.visit(ctx.block());
            if (returnValue != TLValue.VOID) {
                return returnValue;
            }
        }
        return TLValue.VOID;
    }

    @Override
    public TLValue visitTransposeFunctionCall(TransposeFunctionCallContext ctx) {
        TLValue tlValueToTranspose = this.visit(ctx.expression());
        RealMatrix matrixToTranspose = null;
        if (tlValueToTranspose.isMatrix()) {
            double[][] argAsMatrix = TLValue.toMatrix(tlValueToTranspose);
            matrixToTranspose = MatrixUtils.createRealMatrix(argAsMatrix);
        } else if (tlValueToTranspose.isVector()) {
            double[] argsAsVector = TLValue.toVector(tlValueToTranspose);
            matrixToTranspose = MatrixUtils.createColumnRealMatrix(argsAsVector);
        }
        if (matrixToTranspose == null) {
            throw new EvalException(TRANSPOSE_ARGUMENT_MISMATCH, ctx);
        }
        return TLValue.fromMatrix(matrixToTranspose.transpose());
    }

    @Override
    public TLValue visitRowsFunctionCall(RowsFunctionCallContext ctx) {
        TLValue elementToGetRows = this.visit(ctx.expression());
        return new TLValue(getElementDimensions(elementToGetRows, true, ctx));
    }

    @Override
    public TLValue visitColumnsFunctionCall(ColumnsFunctionCallContext ctx) {
        TLValue elementToGetColumns = this.visit(ctx.expression());
        return new TLValue(getElementDimensions(elementToGetColumns, false, ctx));
    }

    private int getElementDimensions(TLValue tlValue, boolean getRows, FunctionCallContext ctx) {
        RealMatrix realMatrix = null;
        if (tlValue.isVector()) {
            double[] elementAsVector = TLValue.toVector(tlValue);
            realMatrix = MatrixUtils.createColumnRealMatrix(elementAsVector);
        }
        if (tlValue.isMatrix()) {
            double[][] elementAsMatrix = TLValue.toMatrix(tlValue);
            realMatrix = MatrixUtils.createRealMatrix(elementAsMatrix);
        }
        if (realMatrix == null) {
            throw new EvalException(DIMENSIONS_ARGUMENTS_MISMATCH, ctx);
        }
        return getRows ? realMatrix.getRowDimension() : realMatrix.getColumnDimension();
    }

    @Override
    public TLValue visitDeterminantFunctionCall(DeterminantFunctionCallContext ctx) {
        TLValue argMatrix = this.visit(ctx.expression());
        if (!argMatrix.isMatrix()) {
            throw new EvalException(NOT_SQUARED_MATRIX, ctx);
        }
        double[][] matrixAsArray = TLValue.toMatrix(argMatrix);
        RealMatrix realMatrix = MatrixUtils.createRealMatrix(matrixAsArray);
        LUDecomposition luDecomposition = new LUDecomposition(realMatrix);
        return new TLValue(luDecomposition.getDeterminant());
    }

    @Override
    public TLValue visitMatrixSum(TLParser.MatrixSumContext ctx) {
        TLValue argMatrix = this.visit(ctx.expression());
        RealMatrix realMatrix = null;
        if (argMatrix.isVector()) {
            double[] argAsVector = TLValue.toVector(argMatrix);
            realMatrix = MatrixUtils.createColumnRealMatrix(argAsVector);
        }
        if (argMatrix.isMatrix()) {
            double[][] argAsMatrix = TLValue.toMatrix(argMatrix);
            realMatrix = MatrixUtils.createRealMatrix(argAsMatrix);
        }
        if (realMatrix == null) {
            throw new EvalException(ELEMENTS_SUM_NOT_MATRIX, ctx);
        }
        return new TLValue(getMatrixSum(realMatrix));
    }

    private double getMatrixSum(RealMatrix realMatrix) {
        double result = 0;
        double[][] data = realMatrix.getData();
        for (int i = 0; i < data.length; i++) {
            for (int j = 0; j < data[i].length; j++) {
                result += data[i][j];
            }
        }
        return result;
    }
    
}
