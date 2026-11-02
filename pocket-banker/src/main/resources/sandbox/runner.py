"""Read-only AST restricted worker: data arrives on stdin, no filesystem/network API."""
import ast
import json
import math
import resource
import sys
from decimal import Decimal

ALLOWED_NODES={ast.Module,ast.Assign,ast.Expr,ast.Name,ast.Load,ast.Store,ast.Constant,ast.List,ast.Dict,ast.Tuple,
 ast.BinOp,ast.UnaryOp,ast.BoolOp,ast.Compare,ast.IfExp,ast.Subscript,ast.Slice,ast.For,ast.If,ast.Pass,
 ast.ListComp,ast.DictComp,ast.GeneratorExp,ast.comprehension,ast.Call,ast.keyword,ast.Attribute,
 ast.Add,ast.Sub,ast.Mult,ast.Div,ast.FloorDiv,ast.Mod,ast.Pow,ast.USub,ast.UAdd,ast.Not,ast.And,ast.Or,
 ast.Eq,ast.NotEq,ast.Lt,ast.LtE,ast.Gt,ast.GtE,ast.In,ast.NotIn,ast.Break,ast.Continue,ast.AugAssign}
FUNCTIONS={'len':len,'sum':sum,'min':min,'max':max,'sorted':sorted,'range':range,'list':list,'dict':dict,
 'str':str,'int':int,'float':float,'abs':abs,'round':round,'Decimal':Decimal,'enumerate':enumerate,'zip':zip}
ATTRIBUTES={'get','items','keys','values','lower','upper','startswith','endswith','quantize'}


def validate(code):
 tree=ast.parse(code)
 if len(list(ast.walk(tree)))>2000:raise ValueError('code_too_complex')
 for node in ast.walk(tree):
  if type(node) not in ALLOWED_NODES:raise ValueError('forbidden_syntax')
  if isinstance(node,ast.Name) and node.id.startswith('_'):raise ValueError('forbidden_name')
  if isinstance(node,ast.Attribute) and node.attr not in ATTRIBUTES:raise ValueError('forbidden_attribute')
  if isinstance(node,ast.Call):
   if isinstance(node.func,ast.Name) and node.func.id not in FUNCTIONS:raise ValueError('forbidden_function')
   if not isinstance(node.func,(ast.Name,ast.Attribute)):raise ValueError('forbidden_call')
  if isinstance(node,ast.Assign):
   for target in node.targets:
    if not isinstance(target,(ast.Name,ast.Tuple,ListTarget)):raise ValueError('data_is_read_only')
 return tree


ListTarget=ast.List

def main():
 try:
  payload=json.loads(sys.stdin.buffer.read(1048577))
  code=payload['code'];data=payload['data']
  if len(code)>16000 or len(data)>10000:raise ValueError('input_limit')
  tree=validate(code)
  resource.setrlimit(resource.RLIMIT_CPU,(2,2))
  # Local process fallback also restricts memory. Docker adds read-only fs/no-network independently.
  if sys.platform!='darwin':resource.setrlimit(resource.RLIMIT_AS,(134217728,134217728))
  namespace={'__builtins__':FUNCTIONS,'data':data}
  exec(compile(tree,'<readonly-analysis>','exec'),namespace)
  result=namespace.get('result')
  output=json.dumps({'status':'SUCCEEDED','result':result},default=str,allow_nan=False)
  if len(output)>65536:raise ValueError('result_limit')
  sys.stdout.write(output)
 except BaseException:
  sys.stdout.write(json.dumps({'status':'FAILED','error':'sandbox_execution_rejected'}))
  sys.exit(1)

if __name__=='__main__':main()
