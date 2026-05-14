package com.scoreautobuyer.utils;

import java.util.Stack;
import java.util.StringTokenizer;

public class MathEvaluator {
   public static double evaluate(String expression) throws IllegalArgumentException {
      if (expression != null && !expression.trim().isEmpty()) {
         try {
            expression = expression.replaceAll("\\s+", "");
            String postfix = infixToPostfix(expression);
            return evaluatePostfix(postfix);
         } catch (Exception e) {
            throw new IllegalArgumentException("Неверное математическое выражение: " + expression + " (" + e.getMessage() + ")");
         }
      } else {
         throw new IllegalArgumentException("Пустое выражение");
      }
   }

   private static String infixToPostfix(String expression) {
      StringBuilder result = new StringBuilder();
      Stack<Character> stack = new Stack();

      for(int i = 0; i < expression.length(); ++i) {
         char c = expression.charAt(i);
         if (!Character.isDigit(c) && c != '.') {
            if (c == '(') {
               stack.push(c);
            } else if (c == ')') {
               while(!stack.isEmpty() && (Character)stack.peek() != '(') {
                  result.append(stack.pop()).append(' ');
               }

               if (!stack.isEmpty()) {
                  stack.pop();
               }
            } else if (isOperator(c)) {
               while(!stack.isEmpty() && precedence(c) <= precedence((Character)stack.peek())) {
                  result.append(stack.pop()).append(' ');
               }

               stack.push(c);
            }
         } else {
            while(i < expression.length() && (Character.isDigit(expression.charAt(i)) || expression.charAt(i) == '.')) {
               result.append(expression.charAt(i));
               ++i;
            }

            result.append(' ');
            --i;
         }
      }

      while(!stack.isEmpty()) {
         result.append(stack.pop()).append(' ');
      }

      return result.toString();
   }

   private static double evaluatePostfix(String postfix) {
      Stack<Double> stack = new Stack();
      StringTokenizer tokens = new StringTokenizer(postfix);

      while(tokens.hasMoreTokens()) {
         String token = tokens.nextToken();
         if (isNumber(token)) {
            stack.push(Double.parseDouble(token));
         } else if (isOperator(token.charAt(0))) {
            if (stack.size() < 2) {
               throw new IllegalArgumentException("Недостаточно операндов для операции: " + token);
            }

            double b = (Double)stack.pop();
            double a = (Double)stack.pop();
            double result = performOperation(a, b, token.charAt(0));
            stack.push(result);
         }
      }

      if (stack.size() != 1) {
         throw new IllegalArgumentException("Неверное выражение");
      } else {
         return (Double)stack.pop();
      }
   }

   private static boolean isOperator(char c) {
      return c == '+' || c == '-' || c == '*' || c == '/';
   }

   private static boolean isNumber(String str) {
      try {
         Double.parseDouble(str);
         return true;
      } catch (NumberFormatException var2) {
         return false;
      }
   }

   private static int precedence(char operator) {
      switch (operator) {
         case '*':
         case '/':
            return 2;
         case '+':
         case '-':
            return 1;
         case ',':
         case '.':
         default:
            return -1;
      }
   }

   private static double performOperation(double a, double b, char operator) {
      switch (operator) {
         case '*':
            return a * b;
         case '+':
            return a + b;
         case ',':
         case '.':
         default:
            throw new IllegalArgumentException("Неизвестная операция: " + operator);
         case '-':
            return a - b;
         case '/':
            if (b == (double)0.0F) {
               throw new IllegalArgumentException("Деление на ноль");
            } else {
               return a / b;
            }
      }
   }
}
